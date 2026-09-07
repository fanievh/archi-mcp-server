package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Cross-tree contract test for the public-repo rule: <em>no internal project-management code may
 * appear in source — in code, comments, log strings or tool descriptions.</em>
 *
 * <p>This repo is curated to a public OSS repo. A reader outside the project cannot resolve an
 * internal tracking code. {@code "B44 center-termination detected"} in Archi's error log, or
 * {@code "per review M1"} in a comment, points at something they have no way to look up. One
 * cleanup pass does not keep a tree clean, so the rule is enforced here rather than by review
 * habit, and {@code tools/internal-code-gaps.txt} is this test's known-gap list.</p>
 *
 * <h2>The pawl</h2>
 *
 * <p>Every family in {@link #FAMILIES} must end in exactly one state: <strong>clean</strong> (zero
 * matches, no registry entry — the target), <strong>allowed</strong> ({@link #ALLOWED_VOCABULARY},
 * and only for vocabulary this repo actually publishes), or <strong>deferred</strong> (a registry
 * entry carrying a measured count that is a lower-only ceiling). A family with matches and no entry
 * fails the build. {@link #FAMILY_ENTRY_CEILING} is lowered, never raised, in the same commit that
 * deletes an entry — exactly as {@code tools/size-ratchet.sh} lowers {@code CEILING_LOC}.</p>
 *
 * <h2>Why the shipped surface is not deferrable</h2>
 *
 * <p>A comment is read only by someone already inside the tree. A <em>string literal</em> in the
 * plugin becomes log output or an MCP tool description — it leaves the plugin and reaches a user or
 * an LLM agent. That is the breach the rule is actually about, so
 * {@link #shouldKeepEveryShippedStringLiteralFreeOfInternalCodes()} gates it at zero with no
 * registry escape hatch, while the deferred counts are all comment- or test-tree-only.</p>
 *
 * <h2>Why the metric ids are allowed and the initiative tag was not</h2>
 *
 * <p>The rule exists to strip references nobody outside the project can resolve. The assessor
 * metric ids resolve: {@code docs/glossary.md} defines each against its public JSON field and
 * {@code README.md} points at that table. So they are not internal codes and the rule does not
 * reach them. That exemption is not taken on trust —
 * {@link #shouldAllowOnlyVocabularyThisRepoActuallyPublishes()} re-reads the glossary and fails if a
 * definition is ever deleted, so the allow-list cannot outlive its own justification.</p>
 *
 * <p>The converse case is the initiative tag this rule removed: it decorated those same ids but
 * named a project episode rather than a concept, so it carried nothing a reader could use.</p>
 *
 * <h2>Two failure modes this test is built to avoid</h2>
 *
 * <ol>
 *   <li><strong>Failing on itself.</strong> This class necessarily contains every banned pattern,
 *       as regexes, and the registry documents several of them as worked examples. Both are
 *       skipped by {@link #EXCLUDED_PATHS}, and {@link #shouldExcludeItsOwnSourceFromTheScan()}
 *       proves both exclusions are load-bearing rather than incidental. The registry's exclusion
 *       became necessary only when {@link #PUBLISHED_SURFACE_ROOTS} brought {@code tools/**} into
 *       scope; before that it was genuinely out of scope, and claiming to exclude it would have
 *       described a protection that did not exist.</li>
 *   <li><strong>Unlocatable findings.</strong> Tool descriptions are built by {@code + "..."}
 *       concatenation, so a scanner sees fragments, not whole sentences. Every finding therefore
 *       reports {@code file:line} <em>and</em> the offending fragment.</li>
 * </ol>
 *
 * <p>A family this test does not scan is <strong>unchecked</strong>, never "clean" — the same
 * abstain-rather-than-false-all-clear rule the response format applies to detectors.</p>
 */
public class InternalCodeContractTest {

    /**
     * Maximum number of entries permitted in {@code tools/internal-code-gaps.txt}.
     *
     * <p><strong>LOWER-ONLY.</strong> Drop this by exactly the number of lines removed, in the same
     * commit that removes them. Never raise it: a newly reintroduced code family must be fixed, not
     * admitted. History (lower-only): 15 (initial — the families left standing after the initiative
     * tag, the layout-tier codes, the review-severity codes and every shipped log string were
     * cleared) &rarr; 12 (six families closed outright — audit, halt, rc, story, backlog-ref and
     * wiki-link — paying for the THREE the widened coverage had to admit: row-ref, ac-identifier
     * and sprint-code).
     *
     * <p>15 &minus; 6 + 3 = 12. An earlier version of this note said "the two ... admitted" and
     * did not reconcile, because {@code sprint-code} was expected to land clean and instead landed
     * as a COLLISION entry once its surviving matches turned out to be geometry fixture labels.
     * The constant was right and the story it told was wrong, which is the harder defect to see.</p>
     *
     * <p>That arithmetic is the constraint, not a footnote. This ceiling was at 15 against exactly
     * 15 entries, so a new family could never simply be <em>added</em>: growing coverage meant
     * either driving the new family to zero in the same commit that added it, or closing an
     * existing one to pay for it. Three families were added and land clean
     * ({@code fix-round-code}, {@code lever-code}, {@code epic-code}); three needed an entry and
     * were paid for.</p>
     *
     * <p>The letter-series row met the same wall at 12 against exactly 12 and answered it the same
     * way, which is the point of stating the arithmetic rather than the number. NINE families were
     * added: {@code spec-section-code}, {@code requirement-code}, {@code finding-code},
     * {@code design-decision-code}, {@code workshop-code}, {@code open-question-code} and
     * {@code workstream-acronym} all land CLEAN in both trees and cost nothing, so the whole budget
     * went on the two that could not — {@code gap-code} and {@code letter-story-code}, both of
     * which collide with fixture labels in the test tree and can therefore never reach zero
     * mechanically. Those two were paid for by closing {@code session-code} and
     * {@code decision-code} outright. 12 &minus; 2 + 2 = 12: unchanged, and still never raised.</p>
     *
     * <p>Then 12 &rarr; 11 &rarr; 10 &rarr; 9, the acceptance-criterion row, closing
     * {@code embedded-code-identifier}, {@code ac-identifier} and {@code ac-code}. The first had
     * its letter class WIDENED to include G in the commit that closed it — the widening took it to
     * 37 against a ceiling of 13, and all 24 new sites were closed in that same commit because the
     * ceiling only ever lowers; its own pre-existing 13 followed. Unlike every earlier movement of
     * this constant, not one of these three clicks was paid for by adding a family: all three are
     * plain subtractions bought with cleanup.</p>
     *
     * <p>{@code ac-code} and {@code ac-identifier} stayed SEPARATE FAMILIES to the end, and closing
     * them in one row is not a retraction of that split. Folding the two PATTERNS together would
     * have driven the dashed form's ceiling from 114 to 473 — a raise the pawl forbids — and the
     * two really do cost different things to clean: one is a comment, the other is a rename that
     * drags its cross-references with it. What they share is the row, not the family. A family is
     * folded in when the cleanup MECHANISM is shared, not when the token merely looks similar.</p>
     */
    private static final int FAMILY_ENTRY_CEILING = 9;

    /** Source trees under the rule. Both are scanned at the same strictness. */
    private static final List<String> SCAN_ROOTS = List.of(
            "net.vheerden.archi.mcp/src",
            "net.vheerden.archi.mcp.tests/src");

    /** The tree whose string literals ship to a user or an LLM agent. */
    private static final String SHIPPED_ROOT = "net.vheerden.archi.mcp/src";

    /**
     * Non-Java files that ship <em>whole</em>. The bundle serves these as MCP resources, so their
     * entire text reaches the agent — there is no literal to extract, every byte is shipped.
     * Scanning only {@code .java} would have left the most directly agent-facing content in the
     * plugin unchecked while the gate reported clean.
     */
    private static final List<String> SHIPPED_RESOURCE_ROOTS = List.of(
            "net.vheerden.archi.mcp/resources");

    /**
     * The OSGi bundle descriptors. These are neither {@code src} nor {@code resources}, so until
     * an adversarial review went looking they were outside <em>every</em> surface this class
     * scans — and they were not clean: {@code plugin.properties} carried
     * {@code "# Story S6a: human-owned approval-mode toggle"} and {@code plugin.xml} three
     * {@code "Story 1.4"} comments.
     *
     * <p>They ship. {@code build.properties} lists both in {@code bin.includes}, and
     * {@code plugin.properties} is the externalized-string file whose <em>values</em> are rendered
     * in Archi's menu bar — a shorter path to a user than the resource bundle this class was
     * already widened to cover. Gated at zero for the same reason: whole-file text, no literal to
     * extract.
     */
    private static final List<String> SHIPPED_DESCRIPTOR_FILES = List.of(
            "net.vheerden.archi.mcp/plugin.xml",
            "net.vheerden.archi.mcp/plugin.properties",
            "net.vheerden.archi.mcp/build.properties",
            "net.vheerden.archi.mcp/META-INF/MANIFEST.MF");

    /**
     * Published non-source surfaces. This repo is curated to a <em>public</em> OSS repo, and the
     * rule's reason — "a reader outside the project cannot resolve this" — does not stop at the
     * file extension. A contributor's first two clicks after the README are the CI workflow and
     * the build harness, both of which GitHub renders inline; {@code # AC-5: the harness needs
     * zip} is exactly as unresolvable there as the same sentence in a {@code .java} comment.
     *
     * <p>Scanned whole, like {@link #SHIPPED_RESOURCE_ROOTS}: these are YAML, shell and Markdown,
     * so there is no string literal to extract and every byte is read text. Gated at zero with no
     * registry escape hatch, for the same reason — a deferral here would be a deferral on the
     * most-read files in the repo.</p>
     */
    private static final List<String> PUBLISHED_SURFACE_ROOTS = List.of(
            ".github",
            "tools");

    /**
     * Floor on the number of files the published-surface walk must find <strong>in each root
     * separately</strong>. There are 11 today, but they are split 1 / 10: {@code .github} holds
     * only {@code workflows/ci.yml}.
     *
     * <p>This was an AGGREGATE floor of 5 until a review pointed out what that actually bought:
     * {@code tools/} alone clears 5, so emptying {@code .github} entirely — the CI workflow this
     * class's own rationale calls the most-read file in the repo — would have left the walk
     * reporting a clean surface it never read. A canary that only fires when BOTH roots vanish is
     * not watching the root that can realistically vanish.
     */
    private static final int MINIMUM_FILES_PER_PUBLISHED_ROOT = 1;

    private static final String GAP_FILE = "tools/internal-code-gaps.txt";

    /**
     * Paths the scan skips, matched as repo-relative paths rather than by bare file name.
     *
     * <p>Two files necessarily contain what this test bans. This class holds every banned pattern
     * as a regex, and the registry documents {@code "B44 ..."}, {@code "per review M1"} and
     * {@code H1..H6} as worked examples of what each family catches. Before {@link
     * #PUBLISHED_SURFACE_ROOTS} existed the registry needed no exclusion — it is not a {@code
     * .java} file under a source root, so it was never in scope, and saying otherwise would have
     * described a protection that did not exist. Bringing {@code tools/**} into scope made that
     * true instead, and the exclusion below is what keeps this test from failing on its own
     * evidence.</p>
     *
     * <p>Matched by <strong>path</strong>, not by file name: a bare-name match would silently
     * exempt any future {@code internal-code-gaps.txt} anywhere in the tree, which is a wider
     * hole than the one being patched.</p>
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
            "net.vheerden.archi.mcp.tests/src/net/vheerden/archi/mcp/model/InternalCodeContractTest.java",
            GAP_FILE);

    /**
     * Published vocabulary the rule does not reach, keyed by id to the public JSON field its
     * glossary row must name. Membership is a claim that the id is defined in a document a reader
     * outside the project can open — asserted below, not assumed.
     */
    private static final Map<String, String> ALLOWED_VOCABULARY = Map.of(
            "M1", "nonOrthogonalTerminalCount",
            "M2", "interiorTerminationCount",
            "M3", "zigzagCount",
            "M4", "connectionEdgeCoincidenceCount",
            "M5", "hubPortQualityScore",
            "M6", "layoutTier",
            "R8", "corridorUtilisationScore");

    private static final String GLOSSARY = "docs/glossary.md";

    /**
     * The code families this test knows about, id to pattern.
     *
     * <p>Adding a family here is how coverage grows. A family absent from this map is
     * <em>unchecked</em>, and this test makes no claim about it either way.</p>
     *
     * <p>Note the bug-number pattern permits a trailing letter. Anchoring it as
     * {@code \bB[0-9]{2,3}\b} — the obvious form — cannot match a suffixed code, and that blind
     * spot hid eight shipped log lines in a whole file during the cleanup this test closes.</p>
     */
    private static final Map<String, Pattern> FAMILIES = buildFamilies();

    private static Map<String, Pattern> buildFamilies() {
        Map<String, Pattern> f = new LinkedHashMap<>();
        f.put("initiative-tag", Pattern.compile("Assessor\\.Redesign"));
        f.put("layout-tier-code", Pattern.compile("\\bL[123]\\b"));
        f.put("review-severity-code", Pattern.compile(
                "(?:review|finding|disposition|audit)[^a-zA-Z]{0,12}\\b[MLH][0-9]\\b"
                + "|\\b[MLH][0-9]\\b[^a-zA-Z]{0,12}(?:review|finding|disposition)"));
        f.put("ac-code", Pattern.compile("AC-[0-9]+"));
        // Open-question codes. This family was UNCHECKED rather than clean until now: two
        // sites sat in .github/workflows/ci.yml and tools/ci/pom.xml — both PUBLISHED_SURFACE
        // roots, which are gated at zero with no deferral — and no registered pattern could
        // match them, so the walk read those files and reported them clean. Both were
        // rewritten to state the technical reason directly (the jar-name glob), leaving the
        // family at zero everywhere, so it registers here as CLEAN with no gap-file entry.
        // Keyed `open-question-ref`, NOT `open-question-code`: that key is already taken below
        // by the bare `Q1`-`Q5` spelling, and this map is a plain put — reusing the key would
        // have silently replaced that family rather than adding one, removing a live check
        // while the suite stayed green.
        f.put("open-question-ref", Pattern.compile("\\bOQ-[0-9]+\\b"));
        // Quick-win codes. UNCHECKED on the same footing as `open-question-ref` above, and
        // found the same way: one site in tools/run-tests.sh — a PUBLISHED_SURFACE root gated
        // at zero — which every registered pattern walked straight past. Rewritten to state
        // the behaviour ("each failure names WHICH path is missing"), leaving the family at
        // zero everywhere, so it registers as CLEAN with no gap-file entry.
        f.put("quick-win-code", Pattern.compile("\\bQW-[0-9]+\\b"));
        f.put("bug-code", Pattern.compile("\\bB[0-9]{2,3}[A-Za-z]?\\b"));
        f.put("task-code", Pattern.compile("Task[ -][0-9]+\\.[0-9]+"));
        // Both the bare and the dotted-refinement spelling. `\bH[0-9]\b` alone cannot match
        // `H-3.1` — the dash and the second number put it outside the word boundary — and that
        // blind spot left a hypothesis code shipping in SpacingControlLoop while a review of this
        // very row recorded it as "one site, removed in passing". It was two sites.
        f.put("hypothesis-code", Pattern.compile("\\bH[0-9]\\b|\\bH-[0-9]+(?:\\.[0-9]+)+"));
        f.put("successor-code", Pattern.compile("Successor [A-Z]\\b"));
        // Both separators. `Session [0-9]+` — space only — was the registered form, and it could
        // not match `Session-9`: 28 registered occurrences were really 42, and 12 of the 14 it
        // could not see were in the shipped plugin. This is the cleanest illustration in this map
        // that a count inherits its pattern's blind spots, which is why the separator is widened
        // here rather than given a second family that would report the same code twice.
        f.put("session-code", Pattern.compile("Session[ -][0-9]+"));
        f.put("decision-code", Pattern.compile("Decision-[A-Z]\\.[0-9]|Arm [AB]\\b|α"));
        f.put("rc-code", Pattern.compile("RC-[0-9]+"));
        // Numbered remediation and instrumentation rounds. This family is why the row that added
        // it exists: three of its matches were live in SHIPPED logger format strings while
        // shouldKeepEveryShippedStringLiteralFreeOfInternalCodes reported clean, because a family
        // this map does not carry is UNCHECKED, not clean. A zero-tolerance gate is only as wide
        // as its map, so "the shipped surface is at zero" was true of the families listed above
        // and false as a sentence about the tree.
        //
        // The trailing letter is not optional decoration: anchoring this as
        // {@code \b(?:Fix|PATCH)-[0-9]+\b} — the obvious form — walks straight past `Fix-2a` and
        // `Fix-2b`, because there is no word boundary between the digit and the suffix. That is
        // the same shape of miss that let `B69B` hide eight shipped log lines from `bug-code`.
        f.put("fix-round-code", Pattern.compile("\\b(?:Fix|PATCH)-[0-9]+[a-z]?\\b"));
        // Audit findings. The letter class is [A-Z], not [SQ], and the width is load-bearing:
        // anchoring it on the two letters that happened to be in the tree when this family was
        // written left `audit finding P2`, `audit P2` and `repo-audit T2` invisible while this
        // family carried NO registry entry — i.e. it was certified CLEAN by the pawl while FOUR
        // of its own occurrences were live: one in the plugin, two in test comments, and one in
        // tools/run-tests.sh, which is gated at zero with no deferral. That is the same shape of
        // miss as `\bB[0-9]{2,3}\b` against `B69B`, except here the blind spot was in the letter
        // rather than the suffix. Measured across SCAN_ROOTS *and* PUBLISHED_SURFACE_ROOTS: the
        // widening finds 4 matches and 0 false positives, because the literal context word
        // `audit` does the discriminating. (An earlier version of this note said "3 matches",
        // counting only SCAN_ROOTS — the same one-root-short miss the fourth site punished.)
        f.put("audit-code", Pattern.compile("audit (?:finding )?[A-Z][0-9]+"));
        f.put("halt-code", Pattern.compile("HALT [0-9]"));
        // Both separators. The dashed form was the only one registered until the bundle
        // descriptors were brought into scope and turned out to carry `Story 1.2` and
        // `Story 1.4` — the DOTTED spelling, which `Story [0-9]+-[0-9]+` is structurally
        // incapable of matching. Same defect as `Session [0-9]+` against `Session-9`, and as
        // this family's own letter-spelled sibling: a family is only as wide as its separator
        // class. 5 matches when added, 0 false positives.
        f.put("story-code", Pattern.compile("Story [0-9]+[-.][0-9]+"));
        f.put("backlog-ref", Pattern.compile("backlog-[a-z]"));
        f.put("wiki-link", Pattern.compile("\\[\\[[a-z0-9_-]+\\]\\]"));
        f.put("bmad-path", Pattern.compile("_bmad"));
        // Backlog row numbers. Three spellings were in the tree — `row-774`, `row 775`, `row-703`
        // — so the separator is a class here for the same reason it is on session-code.
        //
        // The THREE-DIGIT width is load-bearing discrimination, not an oversight, and it is
        // written down because an undocumented narrowing is indistinguishable from one. Measured:
        // widening to `[0-9]{1,4}` adds 14 matches and ALL FOURTEEN are false positives — `row 0`
        // through `row 4` are grid row indices in the layout tests (AutoNudgeGroupBoundsFollowup,
        // GroupLayoutCalculator, NestedLayoutOperations). A 4+-digit row number would be missed;
        // that residual is recorded in the registry's UNCHECKED section rather than bought at a
        // 100% false-positive rate.
        f.put("row-ref", Pattern.compile("\\brow[- ][0-9]{3}\\b"));
        // NOT a family: `Phase N.N` was ruled a code here and the ruling was WRONG. See the
        // UNCHECKED section of the registry. The short version: this pattern is dotted-only, so it
        // could not see `Phase 1`, `Phase 2` or `Phase 3` — and reading its silence as "there is no
        // Phase 2 anywhere" is the exact mistake this whole class exists to prevent. The sub-phases
        // belong to a coherent Phase 1/2/3 scheme that docs/routing-pipeline.md publishes as
        // section headings, which makes them resolvable architecture vocabulary, not plan codes.
        //
        // Sprint / guardrail codes. COLLISION by construction: `S6` is also a perfectly ordinary
        // geometry fixture label in the routing tests, where it sits beside `E6` and `T6` in an
        // S/E/T source-edge-target naming scheme. The pattern stays broad — narrowing it to a
        // context word would restore the blind spot that let bare `(S3)` through while `audit S3`
        // was caught — and the residue is carried in the registry as a COLLISION, read per site.
        f.put("sprint-code", Pattern.compile("\\bS[0-9]{1,2}[a-z]?\\b"));
        // Workstream lever and epic labels. Both separators, for the same reason session-code
        // carries both: the space-only form registered here was reported CLEAN while `Lever-B`
        // was live at five sites, two of them in the plugin. A family is only as wide as its
        // separator class, and this is the third time in this map that the hyphen spelling was
        // the one nobody looked for.
        f.put("lever-code", Pattern.compile("\\bLever[ -][A-Z]\\b"));
        f.put("epic-code", Pattern.compile("\\bEpic [0-9]+"));
        // Acceptance-criterion numbers WITHOUT the dash, which is how they appear inside
        // identifiers. Deliberately a separate family from ac-code rather than an extra
        // alternative inside it: folding them together would have driven ac-code's ceiling from
        // 114 to 473 — a RAISE the pawl forbids — and would have destroyed the distinction
        // (this arithmetic said "119 to 478" until an adversarial review re-derived it: 119 was
        // ac-code's ceiling TWO rows earlier, before ac-identifier was split out of it, and was
        // carried forward here without being re-measured — the exact inheritance the
        // re-derive-don't-inherit rule exists to stop, committed in a comment explaining a ruling)
        // between the prose form and the identifier form, which have different cleanup costs. A
        // method name is renamed with the manifest and @Ignore references that point at it; a
        // comment is not.
        // The LOWERCASE alternative is not cosmetic. The three uppercase forms above are
        // case-sensitive, so a method named `ac7_3_fixtureA_callerOmitted_triggerFires...` or
        // `ac16_reasonStringFormat_canonicalFixture` was invisible to every one of them — 25
        // matches across three classes, ZERO false positives, and acceptance-criterion codes in
        // method names by any reading. They were found only because the row that drove this
        // family to zero went looking for what its own shape could not see BEFORE claiming the
        // population clean; the uppercase pass had already reported 0. A family reported clean by
        // a pattern that cannot spell the token is UNCHECKED, not clean.
        //
        // (Those two names appear here in their ORIGINAL spelling on purpose. This file is in
        // EXCLUDED_PATHS precisely so a worked example can name the thing it is an example of.)
        // FOUR alternatives, and every one of them exists because a previous version of this
        // family reported ZERO while live codes sat in the tree. The history is worth keeping,
        // because the same mistake was made four times in four different disguises:
        //
        //   1. UPPERCASE ONLY            missed `ac7_3_fixtureA...`, `ac16_reason...`  (25 sites)
        //      -> the CASE.
        //   2. TRAILING \b               missed `_AC7BackCompat`, `_AC14CaseA`,
        //                                `_AC3_grandparentGroupCascades...`             (4 sites)
        //      -> the BOUNDARY. \b demands a NON-WORD char next; a letter is a word char, and so
        //         is an underscore. Fixed with (?![0-9]), which asks the only thing that matters:
        //         that the number has ended.
        //   3. LEADING \b ON THE LOWERCASE FORM ONLY   missed `..._ac5`, `fix2_ac7b_...` (3 sites)
        //      -> the ASYMMETRY. The uppercase form had BOTH `_AC[0-9]+` and `\bAC[0-9]+`; the
        //         lowercase form was given only the `\b` one. `_` is a word character, so `\b`
        //         never fires after it and every `_ac<N>` identifier was invisible. Fixed with a
        //         lookbehind that treats `_` as a boundary, which is what `\b` was assumed to do.
        //   4. and the fix for (3) must NOT re-introduce a false positive: a hex id such as
        //      `id-ac3af9994938480a94fe757bba5c292e` starts `ac3a...`. `[a-z]?(?![0-9a-z])` admits
        //      a ONE-letter sub-label (`ac7b` is a real code) while rejecting a hex run.
        //
        // Each of 1-3 was found only by going looking for what the CURRENT shape could not see,
        // AFTER it had reported clean. The count was never the thing that was wrong.
        f.put("ac-identifier", Pattern.compile(
                "_AC[0-9]+(?![0-9])|\\bAC[0-9]+(?![0-9])|\\bAC [0-9]+\\b"
                + "|(?<![a-zA-Z0-9])ac[0-9]+[a-z]?(?![0-9a-z])"));
        // ---- The letter-series families -------------------------------------------------------
        //
        // Every family above is anchored on a keyword (`Task`, `Session`, `Story`) or on a letter
        // whose meaning this tree fixed long ago (`B` bug, `H` hypothesis). The families below are
        // the residue that shape could never reach: a bare capital letter and a digit, which is
        // simultaneously the commonest code spelling in this repo and the commonest FIXTURE LABEL.
        // They are added one letter at a time, each measured, rather than as one blanket
        // `[A-Z][0-9]` family — that blanket was rejected on evidence, because roughly half of the
        // raw shape population is `E3`/`T0`/`C1`-style geometry and oracle labels that are not
        // codes at all and must never be touched.
        //
        // Architecture-spec section ids. These resolve — and that is the point. The cited document
        // is `_bmad-output/implementation-artifacts/control-loop-redesign-spike-2026-05-15/
        // architecture-spec.md`, which really does carry `## 1.10 Open items` with literal `O1`
        // and `O6` rows, exactly as the comments claimed. But it lives in a tree this rule forbids
        // source to cite at all, so it cannot be what makes the citation resolvable, for the same
        // reason as `requirement-code` below. An earlier version of this note called the citation
        // DEAD and said no such file existed; that was wrong — the file was found by truncating a
        // `find` at ten results when there were nineteen, and reading the truncation as absence.
        // The ruling did not depend on it, but a false reason in a gate's own source is how the
        // gate stops being believed.
        f.put("spec-section-code", Pattern.compile("\\bO[0-9]\\b"));
        // Requirement ids from the product requirements doc. `N?` because the non-functional
        // siblings share the shape, and the width is 1-2 digits because `FR4` and `FR17` both
        // occur. These resolve only in `_bmad-output/**`, which this rule already forbids source
        // from citing — a tree the rule bans you from naming cannot also be the thing that makes
        // your citation resolvable.
        f.put("requirement-code", Pattern.compile("\\bN?FR[0-9]{1,2}\\b"));
        // Investigation and cross-review finding codes. `FA?` folds the one-letter and two-letter
        // spellings into one family because they are the same kind of reference (`F4 closure`,
        // `cross-LLM-review FA2`) with the same cleanup cost — unlike `ac-code` versus
        // `ac-identifier`, where a comment and a method name cost very different things to fix.
        f.put("finding-code", Pattern.compile("\\bFA?[0-9]\\b"));
        // Design-decision ids. These are the row's hardest case and the reason its ruling had to
        // be made before any edit: `_bmad-output/design/…` really does carry `## 4. Decisions`
        // with `### D5` and `### D6` as headings, so the citations resolve EXACTLY — the same
        // shape as the `Phase 1.1` rename this map got wrong and reverted. What separates them is
        // WHERE the heading lives. `Phase 1.1` is published in docs/routing-pipeline.md, which
        // this repo ships and points readers at; `### D5` is in a tree the rule forbids source to
        // cite at all. A document you may not name cannot be what makes your citation resolvable.
        f.put("design-decision-code", Pattern.compile("\\bD[1-6]\\b"));
        // Working-session workshop labels (`the W2 lever`, `the W3 Lever-B successor`).
        f.put("workshop-code", Pattern.compile("\\bW[0-9]\\b"));
        // Open-question dispositions. The negative lookahead is load-bearing and measured: the
        // ONLY shaped token in any shipped string literal in this plugin is `Q3 2026` in an
        // example model purpose, where Q3 is a CALENDAR QUARTER - ordinary English a reader
        // resolves without looking anything up. Without the lookahead this family would fail the
        // zero-tolerance shipped-literal gate on a sentence that is not a breach, which is how a
        // detector loses the right to be believed. A year suffix discriminates cleanly: it costs
        // one false negative nobody has ever written (`Q4 2026` as a code) to buy back the only
        // false positive in the tree.
        f.put("open-question-code", Pattern.compile("\\bQ[1-5]\\b(?! [0-9]{4})"));
        // Workstream acronym and its track letters. Named here because it is the counter-example
        // to `M1`-`M6`: docs/bibliography.md DOES carry "(HPRPS Track-A, Axis-3)", so a grep for
        // publication finds a hit - but the acronym is never expanded anywhere in the repo. The
        // allow-list bar is a DEFINITION a reader can resolve, which the glossary supplies for
        // the metric ids and nothing supplies for this one. A mention is not a definition.
        f.put("workstream-acronym", Pattern.compile("\\bHPRPS\\b|\\bTrack[- ][AB]\\b"));
        // Gap codes, and the row's clearest proof that this population is SITE-separable rather
        // than TOKEN-separable. `G1` is a real gap code in the plugin ("G1: validate semantic
        // attributes...") AND a group fixture label in the routing tests ("// Group G1: x=[0,
        // 200]") at the same time, in the same tree. No pattern keyed on the token can be right
        // about both, so this family is deliberately BROAD and the fixture labels are carried in
        // the registry as a COLLISION to be read per site - the call the predecessor made on
        // `S6` beside `E6`/`T6`, at 19 sites instead of 2.
        //
        // The one place a bare token could NOT be tolerated was the shipped resource bundle,
        // which is gated at zero with no deferral: an ArchiMate roadmap recipe drew its `Gap`
        // elements as `│ G1│` boxes under a `┌Gap┐` header. That is diagram syntax carrying its
        // own visible label on the line above - the Mermaid-node-id collision exactly - so the
        // diagram was relabelled rather than the gate narrowed.
        f.put("gap-code", Pattern.compile("\\bG[0-9]{1,2}\\b"));
        // Letter-spelled story codes. `story-code` above is `Story [0-9]+-[0-9]+` and is
        // structurally incapable of matching `Story C3b` or `Story C4` - the same shape of miss
        // as `Session [0-9]+` against `Session-9`, in the alphabet rather than the separator.
        // Anchored at C3-C5 because `C1`/`C2` are oracle-datapoint and node labels in the tests,
        // measured at 34 matches of pure collision; including them would have bought nothing and
        // cost the family its credibility.
        f.put("letter-story-code", Pattern.compile("\\bC[3-5][a-c]?\\b"));

        // Scanned so the allow-list is an ACTIVE exemption rather than an absence of coverage: the
        // published ids are subtracted by name, so a sibling nobody has published — a routing-tier
        // shorthand, a retired metric number, a workstream label — fires here instead of slipping
        // through on family resemblance with the ids that are published.
        f.put("unpublished-metric-sibling", Pattern.compile("\\b[MR][0-9]\\b"));
        // A code buried inside an identifier has no word boundary around it, so every pattern
        // above walks straight past `emitR2Diagnostic` and `B41_WEIGHT_PROP`. These alternatives
        // match the code ITSELF — camelCase-embedded, then CONSTANT_CASE leading and interior —
        // so the matched text is the bare code and the allow-list can subtract it by name, letting
        // `computeM4Count` and `R8_FLOOR` through while `R2_DIAG_FLAG` fires.
        // The letter class covers M/R metric-shaped, B bug, H hypothesis, L tier, S/Q audit and
        // G capability-gap codes.
        //
        // IT DOES NOT SPAN EVERY FAMILY ABOVE THAT USES A LETTER, and an earlier version of this
        // note claimed it did — written when the claim was true, and left standing while NINE
        // letter-series families were added after it (gap-code, letter-story-code,
        // design-decision-code, spec-section-code, finding-code, open-question-code,
        // workshop-code, requirement-code, workstream-acronym). Not one of their letters was added
        // here, so the sentence described a protection that did not exist. `G` was the one that
        // mattered: `\bG[0-9]{1,2}\b` cannot match `StylingHelperG5Test` (no word boundary on
        // either side) and `[MRBHLSQ]` excluded the letter, so three test CLASS NAMES carrying a
        // capability-gap code were counted by NOTHING — unchecked, not clean. `G` is in the class
        // now and those names are gone.
        //
        // The remaining letters stay OUT, each on a MEASURED false-positive count rather than a
        // guess — widening to claim coverage costs more trust than it buys:
        //   +T   7 matches,   7 FP — all PARALLEL_GAP_NARROW_T1_PX, a named threshold
        //   +D   3 matches,   3 FP — all "\uD83D…" UTF-16 surrogate escapes in emoji literals
        //   +W   1 match,     1 FP — http://www.w3.org/2000/svg
        //   +C 124 matches, ~all FP — v4_c0…c3 fixture ids, C3_JSON_MAPPER
        //   +V 109 matches, ~all FP — V1_NAME_IDX/V0_HEIGHT_IDX SWT FontData version indices
        //   +P 160 matches, ~all FP — p10 in parallelConnectionGap_V_p10, a PUBLISHED metric
        //   +N   6 matches,   4 FP — n20/n40 numeric params; 2 real
        //   +K   1 match,     1 FP — K12, a best-of-K algorithm parameter
        //   +A   1 match,     0 FP — decisionA13ContractPreserved: real, but one site, and left
        //                            UNCHECKED and recorded rather than bought for free here
        //   +F +O +E +I  zero matches — a no-op, not a clean bill
        f.put("embedded-code-identifier", Pattern.compile(
                "(?<=[a-z])[MRBHLSQG][0-9]{1,3}(?=[A-Z])"
                + "|\\b[MRBHLSQG][0-9]{1,3}(?=_[A-Z])"
                + "|(?<=_)[MRBHLSQG][0-9]{1,3}(?=_)"
                // Lowercase dotted or underscored form. A JVM property key
                // "archi.mcp.weights.b41" is a string literal in the shipped plugin, but every
                // uppercase pattern above walks past it — which is exactly how three of them
                // survived until this test was written.
                + "|(?<=[._])[mrbhg][0-9]{1,3}(?![a-zA-Z0-9])"));
        return f;
    }

    // ---- The shipped surface: gated at zero, no registry escape hatch --------------------------

    /**
     * The rule's core case. These strings become log output and MCP tool descriptions, so they
     * leave the plugin: a user reads an unresolvable code in Archi's error log, and an LLM agent
     * reads one in a tool schema it is being asked to act on.
     */
    @Test
    public void shouldKeepEveryShippedStringLiteralFreeOfInternalCodes() {
        List<Finding> findings = new ArrayList<>();
        for (SourceLine line : readSource(List.of(SHIPPED_ROOT))) {
            for (String literal : stringLiteralsOf(line.text())) {
                findings.addAll(matchFamilies(line, literal));
            }
        }
        assertTrue("Internal codes found in string literals in " + SHIPPED_ROOT + ". These reach a "
                + "user (log output) or an LLM agent (tool descriptions), so there is no deferral "
                + "for them — replace each with the technical rationale it stands in for. "
                + describe(findings),
                findings.isEmpty());
    }

    /**
     * The other half of the shipped surface. These files are served to the agent whole, so unlike
     * Java sources there is no literal to extract — every byte is shipped text. Scanning only
     * {@code .java} left the most directly agent-facing content in the bundle unchecked while this
     * class reported the shipped surface clean.
     */
    @Test
    public void shouldKeepEveryShippedResourceFileFreeOfInternalCodes() {
        List<Finding> findings = new ArrayList<>();
        for (SourceLine line : readAllFiles(SHIPPED_RESOURCE_ROOTS)) {
            findings.addAll(matchFamilies(line, line.text()));
        }
        assertTrue("Internal codes found in files under " + SHIPPED_RESOURCE_ROOTS + ". These are "
                + "served to the LLM agent as MCP resources in their entirety, so there is no "
                + "deferral for them either. " + describe(findings),
                findings.isEmpty());
    }

    /**
     * The third shipped surface, and the one nothing watched until an adversarial review asked
     * what sits between {@code src} and {@code resources}. The bundle descriptors are siblings of
     * both, so {@link #SCAN_ROOTS} (which is {@code .java}-filtered anyway) and
     * {@link #SHIPPED_RESOURCE_ROOTS} both walked straight past them while this class reported the
     * shipped surface clean. They were not clean.
     *
     * <p>Gated at zero, with no deferral, on the same reasoning as the resource bundle: these are
     * whole-file text with no literal to extract, they are listed in {@code build.properties}
     * {@code bin.includes} so they ship, and {@code plugin.properties} in particular becomes the
     * label text of Archi's own menu items.
     */
    @Test
    public void shouldKeepEveryShippedBundleDescriptorFreeOfInternalCodes() {
        List<SourceLine> lines = readAllFiles(SHIPPED_DESCRIPTOR_FILES);

        // Same canary as the published surface: a walk that found nothing reports "clean"
        // identically to a real clean scan, and these are named FILES rather than roots, so a
        // rename would silently empty the list.
        long files = lines.stream().map(SourceLine::file).distinct().count();
        assertEquals("the shipped-descriptor walk read " + files + " of "
                + SHIPPED_DESCRIPTOR_FILES.size() + " named files. A missing one means this test "
                + "is certifying a file it never opened.",
                SHIPPED_DESCRIPTOR_FILES.size(), (int) files);

        List<Finding> findings = new ArrayList<>();
        for (SourceLine line : lines) {
            findings.addAll(matchFamilies(line, line.text()));
        }
        assertTrue("Internal codes found in the OSGi bundle descriptors. These SHIP — "
                + "build.properties lists them in bin.includes, and plugin.properties supplies the "
                + "labels Archi renders in its own menus — so there is no deferral for them. "
                + describe(findings),
                findings.isEmpty());
    }

    /**
     * The published non-source surface. A public repo's CI workflow and build harness are read by
     * more people than most of its source: GitHub renders them inline, and they are where a
     * contributor looks first when a build fails. {@code # M0-1 — Headless build + test harness}
     * greets that reader with a story code they cannot resolve.
     *
     * <p>Gated at zero like the shipped resources, and for the same reason — these files carry no
     * string literals to extract, so every byte is read text, and a deferral on the most-read
     * files in the repo would be a deferral on the rule's whole point.</p>
     */
    @Test
    public void shouldKeepEveryPublishedNonSourceFileFreeOfInternalCodes() {
        List<SourceLine> lines = readAllFiles(PUBLISHED_SURFACE_ROOTS);

        // A walk that found nothing reports "clean" identically to a real clean scan. An emptied
        // root, a renamed one, or a sparse checkout would otherwise certify a surface nobody read.
        // PER ROOT, not aggregate: the roots are 1 file and 10 files, so any aggregate floor high
        // enough to be meaningful for tools/ is automatically satisfied by tools/ alone, and
        // .github could empty without a sound.
        for (String root : PUBLISHED_SURFACE_ROOTS) {
            long inRoot = readAllFiles(List.of(root)).stream()
                    .map(SourceLine::file).distinct().count();
            assertTrue("the published-surface walk found " + inRoot + " file(s) under '" + root
                    + "'. That root is empty or mis-named, so this test would be reporting a clean "
                    + "surface it never actually read.",
                    inRoot >= MINIMUM_FILES_PER_PUBLISHED_ROOT);
        }

        List<Finding> findings = new ArrayList<>();
        for (SourceLine line : lines) {
            findings.addAll(matchFamilies(line, line.text()));
        }
        assertTrue("Internal codes found under " + PUBLISHED_SURFACE_ROOTS + ". This repo is "
                + "curated to a public OSS repo and these files are rendered by GitHub to anyone "
                + "who opens it, so there is no deferral for them either. " + describe(findings),
                findings.isEmpty());
    }

    /**
     * A per-family ceiling is counted across both trees, so a family could hold its total while
     * migrating occurrences from the test tree into the plugin. Where the registry makes the
     * stronger claim that the main plugin is already at zero, that claim is asserted rather than
     * left as prose nothing checks.
     */
    @Test
    public void shouldHoldTheMainPluginAtZero_forEveryFamilyTheRegistryClaimsIsClearedThere()
            throws IOException {
        Map<String, RegistryEntry> registry = readRegistry();
        List<SourceLine> plugin = readSource(List.of(SHIPPED_ROOT));
        List<String> broken = new ArrayList<>();

        for (Map.Entry<String, RegistryEntry> entry : registry.entrySet()) {
            if (!entry.getValue().reason.contains("MAIN PLUGIN IS AT ZERO")
                    || !FAMILIES.containsKey(entry.getKey())) {
                continue;
            }
            List<Finding> findings = scan(plugin, entry.getKey());
            if (!findings.isEmpty()) {
                broken.add(entry.getKey() + ": " + findings.size() + " in the plugin, first at "
                        + findings.get(0));
            }
        }

        assertTrue("These families claim 'MAIN PLUGIN IS AT ZERO' in " + GAP_FILE + " but no longer "
                + "hold it. A combined-tree ceiling cannot catch this on its own — the total stays "
                + "put while occurrences move into the plugin: " + broken,
                broken.isEmpty());
    }

    // ---- The pawl -----------------------------------------------------------------------------

    /** A family with matches and no registry entry is how the rule quietly stops being enforced. */
    @Test
    public void shouldClassifyEveryFamily_soANewCodeFamilyMustRegisterOrFail() throws IOException {
        Map<String, RegistryEntry> registry = readRegistry();
        List<SourceLine> source = readSource(SCAN_ROOTS);
        TreeSet<String> unclassified = new TreeSet<>();

        for (String family : FAMILIES.keySet()) {
            List<Finding> findings = scan(source, family);
            if (!findings.isEmpty() && !registry.containsKey(family)) {
                unclassified.add(family + " (" + findings.size() + " matches, first at "
                        + findings.get(0) + ")");
            }
        }

        assertTrue("These code families have matches but no entry in " + GAP_FILE + ". Either "
                + "remove the codes, or add a deferral line with its measured count — a family "
                + "nothing records is how this rule gets re-broken: " + unclassified,
                unclassified.isEmpty());
    }

    /**
     * The reverse direction. An entry for a family that is already clean quietly reserves headroom
     * for reintroducing it, so closing a family must delete its line and click the ceiling.
     */
    @Test
    public void shouldRejectStaleRegistryEntries_whenAFamilyIsAlreadyClean() throws IOException {
        Map<String, RegistryEntry> registry = readRegistry();
        List<SourceLine> source = readSource(SCAN_ROOTS);
        TreeSet<String> stale = new TreeSet<>();

        for (String family : registry.keySet()) {
            if (!FAMILIES.containsKey(family)) {
                stale.add(family + " (names no known family)");
            } else if (scan(source, family).isEmpty()) {
                stale.add(family + " (now clean)");
            }
        }

        assertTrue("These " + GAP_FILE + " entries are dead weight. Delete each one and lower "
                + "FAMILY_ENTRY_CEILING by the number deleted, in the same commit: " + stale,
                stale.isEmpty());
    }

    /** The per-family ratchet: a deferred count may shrink, never grow. */
    @Test
    public void shouldKeepEachFamilyAtOrBelowItsCeiling_whichOnlyEverLowers() throws IOException {
        Map<String, RegistryEntry> registry = readRegistry();
        List<SourceLine> source = readSource(SCAN_ROOTS);
        List<String> over = new ArrayList<>();

        for (Map.Entry<String, RegistryEntry> entry : registry.entrySet()) {
            if (!FAMILIES.containsKey(entry.getKey())) {
                continue;
            }
            List<Finding> findings = scan(source, entry.getKey());
            if (findings.size() > entry.getValue().ceiling) {
                over.add(entry.getKey() + ": " + findings.size() + " matches against a ceiling of "
                        // "last in scan order", NOT "the one you just added" — this is sorted by
                        // file then line, so on a multi-file family it routinely points at a
                        // site that has been there for months. Reverting one file to RED-prove
                        // this ratchet reported a gap-code finding in a file the revert never
                        // touched, which is a false pointer straight into the wrong place.
                        + entry.getValue().ceiling + "; last in scan order at "
                        + findings.get(findings.size() - 1));
            }
        }

        assertTrue("These families grew past their recorded count. The ceilings in " + GAP_FILE
                + " are LOWER-ONLY: remove the new codes rather than raising the number: " + over,
                over.isEmpty());
    }

    /** The registry ratchet: the number of admitted families may shrink, never grow. */
    @Test
    public void shouldKeepTheRegistryAtOrBelowItsEntryCeiling_whichOnlyEverLowers()
            throws IOException {
        int entries = readRegistry().size();
        assertTrue("The internal-code gap registry has " + entries + " entries against a ceiling of "
                + FAMILY_ENTRY_CEILING + ". The ceiling is LOWER-ONLY: close a family and drop the "
                + "ceiling, never the reverse.",
                entries <= FAMILY_ENTRY_CEILING);
    }

    /** Every entry must carry a status, a count and a reason, so the registry stays reviewable. */
    @Test
    public void shouldRequireAStatusACountAndAReasonOnEveryRegistryEntry() throws IOException {
        List<String> malformed = new ArrayList<>();
        for (Map.Entry<String, RegistryEntry> entry : readRegistry().entrySet()) {
            RegistryEntry value = entry.getValue();
            boolean statusOk = "DEFERRED".equals(value.status) || "COLLISION".equals(value.status);
            if (!statusOk || value.ceiling < 0 || value.reason.length() < 20) {
                malformed.add(entry.getKey());
            }
        }
        assertTrue("Registry entries must read '<family> <DEFERRED|COLLISION> <count> # <reason>'. "
                + "Malformed: " + malformed, malformed.isEmpty());
    }

    // ---- The allow-list cannot outlive its justification ---------------------------------------

    /**
     * The exemption's whole argument is that these ids resolve for a reader outside the project. If
     * the glossary row that makes that true is ever deleted, the argument evaporates and the ids
     * become exactly the kind of unresolvable token the rule bans. This fails at that moment rather
     * than leaving a stale exemption in place.
     */
    @Test
    public void shouldAllowOnlyVocabularyThisRepoActuallyPublishes() throws IOException {
        String glossary = Files.readString(locateRepoFile(GLOSSARY), StandardCharsets.UTF_8);
        TreeSet<String> unpublished = new TreeSet<>();

        for (Map.Entry<String, String> allowed : ALLOWED_VOCABULARY.entrySet()) {
            // Same LINE, not merely same file. Two independent whole-document `contains` calls
            // would keep passing after the definition row was gutted, as soon as the field name
            // happened to appear anywhere else — which is exactly the kind of accidental pass this
            // check exists to rule out.
            boolean definedInOneRow = glossary.lines().anyMatch(row ->
                    row.contains("**" + allowed.getKey() + "**") && row.contains(allowed.getValue()));
            if (!definedInOneRow) {
                unpublished.add(allowed.getKey() + " (no single glossary row carries both '**"
                        + allowed.getKey() + "**' and '" + allowed.getValue() + "')");
            }
        }

        assertTrue("These ids are allow-listed as published vocabulary but " + GLOSSARY + " no "
                + "longer defines them against their public field. The allow-list exists ONLY "
                + "because a reader can resolve them there — restore the glossary row, or drop the "
                + "id from ALLOWED_VOCABULARY and clean it out of the source: " + unpublished,
                unpublished.isEmpty());
    }

    /** The allow-list is deliberately narrow: an unpublished tier code is not vocabulary. */
    @Test
    public void shouldNotAllowLayoutTierCodes_whichHaveNoGlossaryRow() throws IOException {
        String glossary = Files.readString(locateRepoFile(GLOSSARY), StandardCharsets.UTF_8);
        for (String tier : List.of("L1", "L2", "L3")) {
            assertFalse("If " + GLOSSARY + " ever defines " + tier + " as vocabulary, this rule "
                    + "must be revisited — until then a layout tier is written 'Tier 1L', the "
                    + "spelling the published docs use.",
                    glossary.contains("**" + tier + "**"));
            assertFalse(tier + " must not be allow-listed while it has no glossary row",
                    ALLOWED_VOCABULARY.containsKey(tier));
        }
    }

    // ---- Self-exclusion (this class holds every banned pattern) --------------------------------

    /**
     * Proves the exclusion is load-bearing rather than incidental: this class's own source really
     * does contain matches, and the scan really does skip it.
     */
    @Test
    public void shouldExcludeItsOwnSourceFromTheScan() {
        Path own = locateRepoFile(SCAN_ROOTS.get(1))
                .resolve("net/vheerden/archi/mcp/model/InternalCodeContractTest.java");
        assertTrue("this test's own source must exist where expected", Files.exists(own));

        String text = readFile(own);
        assertTrue("this class is expected to contain banned patterns as regexes — if it no longer "
                + "does, the self-exclusion below is no longer proving anything",
                FAMILIES.get("bug-code").matcher(text).find()
                        || FAMILIES.get("ac-code").matcher(text).find());

        for (SourceLine line : readSource(SCAN_ROOTS)) {
            assertFalse("the scan must skip " + own.getFileName() + " — it holds every banned "
                    + "pattern by construction, so scanning it would fail on itself",
                    line.file().getFileName().toString().equals(own.getFileName().toString()));
        }

        // The registry's exclusion is the newer of the two and the easier to lose: it only became
        // necessary when tools/** joined PUBLISHED_SURFACE_ROOTS, so nothing about the source scan
        // would notice if it were dropped. Proved the same way — the file really does contain
        // matches, and the published-surface walk really does skip it.
        Path registry = locateRepoFile(GAP_FILE);
        assertTrue("the registry is expected to document banned patterns as worked examples — if "
                + "it no longer does, its exclusion is no longer proving anything",
                FAMILIES.get("bug-code").matcher(readFile(registry)).find());

        for (SourceLine line : readAllFiles(PUBLISHED_SURFACE_ROOTS)) {
            assertFalse("the published-surface scan must skip " + GAP_FILE + " — it documents "
                    + "banned codes as examples of what each family catches, so scanning it would "
                    + "fail this test on its own evidence",
                    line.file().toString().replace('\\', '/').endsWith(GAP_FILE));
        }
    }

    // ---- Findings must be locatable ------------------------------------------------------------

    /**
     * Tool descriptions are assembled by {@code + "..."} concatenation, so a source-text scanner
     * sees fragments rather than whole sentences. A finding that named only a line number would
     * leave the reader hunting through a fifty-line description for which piece matched.
     */
    @Test
    public void shouldReportFileLineAndFragment_soAConcatenatedDescriptionHitIsLocatable() {
        SourceLine line = new SourceLine(Path.of("a/b/ViewPlacementHandler.java"), 2317,
                "        + \"M4 edge-coincidence, see AC-7 for the threshold \"");
        List<Finding> findings = matchFamilies(line, "M4 edge-coincidence, see AC-7 for the threshold ");

        assertEquals("exactly the ac-code family should match this fragment", 1, findings.size());
        Finding finding = findings.get(0);
        assertEquals("ac-code", finding.family);
        assertEquals("AC-7", finding.fragment);

        String rendered = finding.toString();
        assertTrue("a finding must name its file: " + rendered,
                rendered.contains("ViewPlacementHandler.java"));
        assertTrue("a finding must name its line: " + rendered, rendered.contains("2317"));
        assertTrue("a finding must quote the offending fragment, not just the line: " + rendered,
                rendered.contains("AC-7"));
    }

    /** The metric ids must survive that same fragment untouched — the allow-list in action. */
    @Test
    public void shouldNotFlagAPublishedMetricId_whenItAppearsInAToolDescriptionFragment() {
        SourceLine line = new SourceLine(Path.of("a/b/ViewPlacementHandler.java"), 2317,
                "        + \"M4 connection-vs-edge coincidence, M5 hub-port quality, R8 corridors \"");
        List<Finding> findings =
                matchFamilies(line, "M4 connection-vs-edge coincidence, M5 hub-port quality, R8 corridors ");

        assertTrue("published metric ids are vocabulary, not internal codes — nothing should fire "
                + "on a description that only names them: " + describe(findings),
                findings.isEmpty());
    }

    /**
     * The shipped-surface gate reads one line at a time, so it cannot see inside a text block —
     * a code shipped in one would evade a check that claims zero tolerance. There are none in the
     * tree today. This fails the moment that stops being true, rather than letting the gate keep
     * reporting clean over a hole: an unscanned construct is <em>unchecked</em>, never clean.
     */
    @Test
    public void shouldFailIfATextBlockAppears_becauseTheLineScopedExtractorCannotSeeInsideOne() {
        List<String> offenders = new ArrayList<>();
        for (SourceLine line : readSource(List.of(SHIPPED_ROOT))) {
            if (line.text().contains("\"\"\"")) {
                offenders.add(line.file() + ":" + line.number());
            }
        }
        assertTrue("A text block appeared in " + SHIPPED_ROOT + ". stringLiteralsOf is line-scoped "
                + "and cannot read inside one, so the shipped-surface gate would silently stop "
                + "covering it. Teach the extractor about text blocks before landing this: "
                + offenders,
                offenders.isEmpty());
    }

    /** The extractor's two real-world traps, pinned so a future simplification cannot undo them. */
    @Test
    public void shouldReadStringLiteralsWithoutBeingFooledByCommentsOrCharLiterals() {
        assertEquals("a closed literal followed by a quote-bearing trailing comment must yield "
                + "only the literal — the comment is not shipped text",
                List.of("Change"),
                stringLiteralsOf("            default -> \"Change\"; // \"+\" additive and unknown"));

        assertEquals("a char literal holding a quote must not open a phantom string",
                List.of("real"),
                stringLiteralsOf("if (c == '\"') { s = \"real\"; }"));

        assertEquals("an escaped quote stays inside its literal",
                List.of("say \"hi\""),
                stringLiteralsOf("String s = \"say \\\"hi\\\"\";"));

        assertEquals("a // inside a literal is not a comment",
                List.of("http://example/M4"),
                stringLiteralsOf("String url = \"http://example/M4\";"));

        assertTrue("a whole-line comment yields nothing",
                stringLiteralsOf("        // B44 was here, quoted \"B44\"").isEmpty());
    }

    // ---- scanning ------------------------------------------------------------------------------

    private static List<Finding> scan(List<SourceLine> source, String family) {
        Pattern pattern = FAMILIES.get(family);
        List<Finding> findings = new ArrayList<>();
        for (SourceLine line : source) {
            Matcher matcher = pattern.matcher(line.text());
            while (matcher.find()) {
                if (isAllowed(matcher.group())) {
                    continue;
                }
                findings.add(new Finding(family, line, matcher.group()));
            }
        }
        return findings;
    }

    private static List<Finding> matchFamilies(SourceLine line, String text) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, Pattern> family : FAMILIES.entrySet()) {
            Matcher matcher = family.getValue().matcher(text);
            while (matcher.find()) {
                if (isAllowed(matcher.group())) {
                    continue;
                }
                findings.add(new Finding(family.getKey(), line, matcher.group()));
            }
        }
        return findings;
    }

    /**
     * The exemption, applied at the point of match: published vocabulary is not an internal code.
     *
     * <p>Case-insensitive because the same id appears lowercased inside identifiers and property
     * keys ({@code beforeM4} versus {@code before.m4}). Matching case-sensitively would flag the
     * lowercase spelling of an id the glossary publishes.</p>
     */
    private static boolean isAllowed(String fragment) {
        return ALLOWED_VOCABULARY.containsKey(fragment.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Extracts Java string literals from one source line.
     *
     * <p>A single left-to-right pass that tracks whether it is inside a literal, because the
     * obvious shortcuts both break on real code in this tree. Stripping the comment first by
     * "is there a quote before the first {@code //}?" mis-fires on
     * {@code default -> "Change"; // "+" additive}: a quote does precede the {@code //}, but its
     * literal has already closed, so the trailing comment survives and its quoted fragment is
     * then read as shipped text. And a char literal {@code '"'} would otherwise open a phantom
     * string that swallows the rest of the line.</p>
     *
     * <p>Line-scoped by design: a literal split across a concatenation boundary yields one
     * fragment per line, which is what the fragment reporting exists for. Java string literals
     * cannot span lines, so nothing is lost — except text blocks, which
     * {@link #shouldFailIfATextBlockAppears_becauseTheLineScopedExtractorCannotSeeInsideOne()}
     * refuses to let into the tree silently.</p>
     */
    private static List<String> stringLiteralsOf(String line) {
        List<String> literals = new ArrayList<>();
        StringBuilder current = null;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (current != null) {
                if (c == '\\') {
                    if (i + 1 < line.length()) {
                        current.append(line.charAt(i + 1));
                    }
                    i++;
                } else if (c == '"') {
                    literals.add(current.toString());
                    current = null;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '\'') {
                i = endOfCharLiteral(line, i);
            } else if (c == '"') {
                current = new StringBuilder();
            }
        }
        return literals;
    }

    /** Index of the closing quote of a char literal opened at {@code open}, or end of line. */
    private static int endOfCharLiteral(String line, int open) {
        for (int i = open + 1; i < line.length(); i++) {
            if (line.charAt(i) == '\\') {
                i++;
            } else if (line.charAt(i) == '\'') {
                return i;
            }
        }
        return line.length();
    }

    private static List<SourceLine> readSource(List<String> roots) {
        return read(roots, p -> p.toString().endsWith(".java"));
    }

    /** Every regular file under the roots, regardless of extension — for whole-file resources. */
    private static List<SourceLine> readAllFiles(List<String> roots) {
        return read(roots, p -> true);
    }

    private static List<SourceLine> read(List<String> roots, java.util.function.Predicate<Path> keep) {
        List<SourceLine> lines = new ArrayList<>();
        for (String root : roots) {
            Path dir = locateRepoFile(root);
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(keep)
                        .filter(p -> !isExcluded(p))
                        .sorted()
                        .toList()) {
                    int number = 0;
                    // Trailing \r is a non-word character, so it behaves as end-of-line for every
                    // \b-anchored pattern here; stripping it only keeps reported fragments clean.
                    for (String text : readFile(file).split("\r?\n", -1)) {
                        lines.add(new SourceLine(file, ++number, text));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not walk " + dir, e);
            }
        }
        return lines;
    }

    /**
     * Whether a walked file is one of the two the scan must skip, matched on its repo-relative
     * suffix. {@link #locateRepoFile} hands back absolute paths, so a suffix comparison is the
     * portable way to say "this exact file in the checkout" without reconstructing the repo root.
     */
    private static boolean isExcluded(Path file) {
        String path = file.toString().replace('\\', '/');
        return EXCLUDED_PATHS.stream().anyMatch(path::endsWith);
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            // The published-surface roots take EVERY file regardless of extension, so a committed
            // image, a .DS_Store or a Latin-1 file would otherwise blow the whole class up with an
            // opaque stack trace. It must still fail — an undecodable file is UNCHECKED, never
            // clean — but it fails as a readable finding naming the file, like everything else here.
            throw new AssertionError(file + " could not be decoded as UTF-8, so this scan cannot "
                    + "read it and makes NO claim about its contents. An unreadable file under a "
                    + "gated root is UNCHECKED, not clean: either it does not belong there, or "
                    + "this scan needs to learn to skip its kind deliberately and say so.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** Caps the rendered list so a first run does not bury the message it is trying to deliver. */
    private static String describe(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "None.";
        }
        StringBuilder out = new StringBuilder(findings.size() + " finding(s):");
        int shown = Math.min(findings.size(), 25);
        for (int i = 0; i < shown; i++) {
            out.append("\n  ").append(findings.get(i));
        }
        if (shown < findings.size()) {
            out.append("\n  ... and ").append(findings.size() - shown).append(" more");
        }
        return out.toString();
    }

    // ---- the registry --------------------------------------------------------------------------

    private Map<String, RegistryEntry> readRegistry() throws IOException {
        Path file = locateRepoFile(GAP_FILE);
        Map<String, RegistryEntry> entries = new LinkedHashMap<>();
        int lineCount = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lineCount++;
            String[] head = line.split("\\s+", 4);
            assertTrue("malformed registry entry (want '<family> <STATUS> <count> # <reason>'): "
                    + line, head.length >= 4);
            String family = head[0];
            assertFalse("'" + family + "' is listed twice in " + GAP_FILE + ". Entries are keyed by "
                    + "family, so a duplicate would collapse into one and let the file grow past "
                    + "the ceiling without a click. Merge the two lines into one.",
                    entries.containsKey(family));
            int ceiling;
            try {
                ceiling = Integer.parseInt(head[2]);
            } catch (NumberFormatException e) {
                throw new AssertionError("registry entry count must be a number: " + line);
            }
            String reason = head[3].startsWith("#") ? head[3].substring(1).trim() : "";
            entries.put(family, new RegistryEntry(head[1], ceiling, reason));
        }
        assertEquals("every non-comment line in " + GAP_FILE + " must survive parsing into exactly "
                + "one entry", lineCount, entries.size());
        return entries;
    }

    /**
     * Resolves a repo-relative path by walking upward from the working directory.
     *
     * <p>A runner whose working directory sits outside the repo gets the explicit failure below
     * rather than a silently skipped check — the alternative failure mode for a ratchet is to
     * quietly stop ratcheting.</p>
     */
    private static Path locateRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }

    private record SourceLine(Path file, int number, String text) { }

    private record RegistryEntry(String status, int ceiling, String reason) { }

    /** A single match, rendered as {@code family file:line -> "fragment"}. */
    private record Finding(String family, SourceLine line, String fragment) {
        @Override
        public String toString() {
            return family + " " + line.file() + ":" + line.number() + " -> \"" + fragment + "\"";
        }
    }
}
