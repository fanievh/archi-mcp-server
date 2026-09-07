package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The recipe-copy pawl.
 *
 * <p><strong>What this exists to stop.</strong> The pages under {@code resources/recipes/} are not
 * developer documentation. They are shipped guidance an agent fetches mid-workflow and executes as
 * instructions, so a sentence that contradicts the tool it prescribes is a wrong tool call in the
 * field, not a typo. Two such contradictions shipped undetected: a swimlane recipe whose topology
 * block stacked its lanes top-to-bottom while its own build delta arranged them left-to-right as
 * columns, and a routing table that sent a flat estate-wide view to a hub-and-spoke recipe. Nothing
 * in the build linked the prose to the schema it quotes, or to the source that quotes it back.</p>
 *
 * <p><strong>Why a filesystem scan.</strong> The sibling assertions in {@code ResourceHandlerTest}
 * that read these same bodies open with an {@code Assume} on the PDE classpath, so outside PDE they
 * are <em>skipped</em> — and a skipped test is a green scan. A pin that can silently not run is not
 * a pin. Reading the files directly reaches them in every harness.</p>
 *
 * <p><strong>Known limits, stated rather than implied.</strong></p>
 * <ul>
 *   <li>The schema-conformance scan validates <em>prescribed</em> parameter values. A recipe may
 *       also name a value in order to forbid it ({@code roadmap-migration.md} tells the agent not
 *       to use {@code arrangement: "tree"}), and {@code "tree"} is deliberately not in the tool's
 *       enum. Such clauses are excluded by truncating each line at its prohibition marker. That
 *       exclusion is itself a hole — a prescription written after a prohibition on the same line
 *       would be dropped — so {@link #shouldNotSwallowAPrescriptionThatMerelyFollowsOne} guards
 *       the truncation, and the count pin below would notice the loss.</li>
 *   <li>Conformance to the enum would <em>not</em> have caught the swimlane contradiction:
 *       {@code "horizontal"} is a perfectly legal value, just the wrong one. The targeted
 *       assertions carry that weight, and say why in their own failure messages.</li>
 *   <li>The Assignment scan below reads <em>arrow-form</em> claims — {@code Node→Artifact} inside
 *       an {@code AssignmentRelationship} clause. A recipe can make the same claim in prose
 *       ("their Assignment to the hub"), which no arrow scan can see. Those sites are held by
 *       targeted assertions instead. The alternative — flagging {@code Assignment} wherever it
 *       co-occurs with {@code ApplicationComponent} — was rejected on purpose: it would forbid the
 *       copy from <em>explaining</em> the rule it is obeying, and at a ±300-character window it
 *       also catches {@code behaviour-process-flow.md:47}'s support-layer clause, which names no
 *       pair and claims nothing false. (Measured: that clause sits 227 characters from the nearest
 *       {@code ApplicationComponent}. The {@code role→process} clause on {@code :11} is 3456
 *       characters away and would <em>not</em> be caught — do not cite it as the example.)</li>
 * </ul>
 */
public class RecipeCopyContractTest {

    /** Shipped guidance bodies, relative to either project directory. */
    private static final String[] RESOURCE_ROOTS = {
            "net.vheerden.archi.mcp/resources",
            "../net.vheerden.archi.mcp/resources",
    };

    /** Source root of the production plugin, for the cross-artifact citation check. */
    private static final String[] SOURCE_ROOTS = {
            "net.vheerden.archi.mcp/src",
            "../net.vheerden.archi.mcp/src",
    };

    /** Published technical documentation, for the audit-freshness check. */
    private static final String[] DOC_ROOTS = {
            "docs",
            "../docs",
    };

    /** The committed audit of every relationship the recipe library prescribes. */
    private static final String RELATIONSHIP_AUDIT = "recipe-relationship-audit.md";

    private static final String BEHAVIOUR_PROCESS_FLOW = "behaviour-process-flow.md";
    private static final String INDEX = "index.md";
    private static final String APPLICATION_INTEGRATION = "application-integration.md";
    private static final String TECHNOLOGY_DEPLOYMENT = "technology-deployment.md";
    private static final String ROADMAP_MIGRATION = "roadmap-migration.md";
    private static final String MOTIVATION = "motivation.md";

    /**
     * Number of {@code arrangement:} / {@code direction:} values the recipe library prescribes.
     *
     * <p>Pinned so a scanner that silently matched nothing cannot report a clean bill of health
     * over an empty set — the same reason the pointer scan pins its own count. Current
     * distribution: 10 {@code arrangement}, 5 {@code direction}, across five recipe pages and the
     * index. Update deliberately, in the commit that adds or removes a prescribed value.</p>
     */
    private static final int EXPECTED_PRESCRIBED_VALUES = 15;

    /**
     * Number of {@code Source→Target} pairs the recipe library names inside an
     * {@code AssignmentRelationship} clause.
     *
     * <p>Occurrences, not lines — a single line can carry several, and one line here carries both.
     * Pinned for the reason {@link #EXPECTED_PRESCRIBED_VALUES} is: a scanner that silently matched
     * nothing would report a clean bill of health over an empty set. Current distribution: 4 — two
     * on {@code technology-deployment.md} ({@code Node→Artifact}, {@code Node→SystemSoftware}), one
     * on {@code behaviour-process-flow.md} ({@code ApplicationComponent→ApplicationService}) and one
     * on {@code index.md} ({@code BusinessActor→BusinessRole}).
     * Update deliberately, in the commit that adds or removes one.</p>
     *
     * <p><strong>Why it moved from 3 to 4.</strong> The index's organisation-structure row used to
     * say "exclude Composition/owner-Assignment by nesting" and name no endpoints. Of the four
     * orderings its two types admit, one permits an Assignment — the pair now written out — and
     * three do not, because no concept there may be assigned to its own type and a role may not be
     * assigned to an actor. The same shape as the journey bullet below, found by the same sweep.</p>
     *
     * <p><strong>Why it moved from 2 to 3.</strong> The journey view's nesting bullet used to
     * prescribe "an {@code AssignmentRelationship} within the support layers" and name no endpoints
     * at all. Of the sixteen orderings its own element subset admits, exactly one — the pair now
     * written out — is permitted; the other fifteen are rejected, including every same-type
     * ordering, because no concept there may be assigned to itself. Naming the pair is what makes
     * the clause checkable, and a named pair written with an arrow is by construction one this
     * scanner counts.</p>
     *
     * <p><strong>This count is not the number of Assignment pairs the corpus names.</strong> It is
     * the number written in arrow form inside an {@code AssignmentRelationship} clause, which is
     * this scanner's reach and not the corpus's content. The shipped relationship audit lists more
     * — {@code BusinessRole→BusinessProcess} and {@code BusinessActor→BusinessProcess} among them,
     * derived from prose whose arrow has a lower-case left side that {@link #NAMED_PAIR} cannot
     * match. The two numbers answer different questions and neither is wrong; a reader comparing
     * them without this note would reasonably read a contradiction.</p>
     *
     * <p><strong>What this count cannot tell you.</strong> It moves only for pairs the scanner
     * recognises. A new violation written in a form {@link #NAMED_PAIR} does not match — an ASCII
     * {@code ->}, a reversed arrow, or free prose — contributes nothing, leaves the count unchanged,
     * and passes silently. {@link #shouldParseOnlyWhatItClaimsTo_soTheScannersReachIsWrittenDown}
     * exists to write that reach down rather than leave it implied.</p>
     */
    private static final int EXPECTED_ASSIGNMENT_PAIRS = 4;

    /**
     * The one element type that may never receive an Assignment.
     *
     * <p>Not a rule of thumb — measured against the matrix Archi itself enforces.
     * {@code ArchimateModelUtils.isValidRelationship}, the server's single validity gate, is backed
     * by {@code RelationshipsMatrix}, which loads {@code model/relationships.xml} out of the
     * {@code com.archimatetool.model} bundle in the Archi install. Key letter {@code i} is
     * Assignment. Scanning all 62 source concepts there returns <em>no</em> concept that may Assign
     * into an {@code ApplicationComponent}: {@code Node} and {@code Device} are both
     * {@code fortv}, {@code ApplicationCollaboration} is {@code fgortv}, {@code BusinessActor} and
     * {@code BusinessRole} are {@code fotv}. Re-derive it by parsing that file for
     * {@code source/target} entries and reporting every source whose {@code relations} attribute
     * toward {@code ApplicationComponent} contains an {@code i} — the answer is none.</p>
     *
     * <p>The matrix is deliberately <em>not</em> read from this test: it lives outside the repo
     * under an install path that differs between a developer machine and CI, so a test that could
     * not find it would skip — and a skipped test is a green scan, which is the failure mode this
     * whole class exists to avoid.</p>
     */
    private static final String NEVER_AN_ASSIGNMENT_TARGET = "ApplicationComponent";

    /**
     * A {@code CompositionRelationship} clause: the type name up to the first em-dash.
     *
     * <p>Bounded exactly as {@link #ASSIGNMENT_CLAUSE} is, and for the same reason. The em-dash is
     * where these bullets stop naming pairs and start explaining them, and scoping to the clause is
     * what keeps this guard independent of its neighbours: when the Aggregation defect this story
     * removed is put back, it lands in a <em>later</em> sentence of the same bullet, and an
     * unscoped scan would read its "events into a work package" as a cross-type Composition and
     * redden the overshoot pin as collateral.</p>
     */
    private static final Pattern COMPOSITION_CLAUSE =
            Pattern.compile("CompositionRelationship((?:(?!--)[^—–\n])*)");

    /**
     * An {@code AggregationRelationship} clause, bounded exactly as {@link #COMPOSITION_CLAUSE} is.
     *
     * <p>Aggregation and Composition are the two part-of links, and the corpus prescribes both by
     * nesting rather than by drawing. They need separate clause patterns rather than one alternation
     * because the guards that read them assert different things: a page may legitimately name one
     * and deny the other in the same bullet, and a merged pattern would hand both to the same
     * cross-type check.</p>
     */
    private static final Pattern AGGREGATION_CLAUSE =
            Pattern.compile("AggregationRelationship((?:(?!--)[^—–\n])*)");

    /**
     * One nesting clause inside it: {@code WorkPackage into sub-packages}.
     *
     * <p>Captures the nested-into target rather than merely detecting that two type names share a
     * sentence. The bullet legitimately chains two clauses with "or", so a window-based check that
     * simply looked for {@code Deliverable} somewhere after {@code WorkPackage} flags the CORRECT
     * copy — measured, on this very page. What distinguishes legal from illegal is not adjacency
     * but whether each clause nests a type into a sub-part of its OWN type.</p>
     *
     * <p><strong>The determiner set is a CLOSED enumerated list, not "the determiners English
     * supplies".</strong> An unlisted one ({@code each}, {@code every}, {@code all of its} were the
     * measured gaps, now added) is captured <em>as the target</em>, so a correct edit fails loudly.
     * That failure direction is deliberate and the alternative was measured and rejected: skipping
     * lowercase words generically fixes the determiner case and <em>loses a lower-case cross-type
     * target</em> when a legal nesting follows it in the same clause — {@code "X into services or
     * sub-components"} then yields only {@code sub-components}, and the guard reports clean over a
     * real defect. A loud false alarm on an unlisted determiner is the price of never trading this
     * scan into a silent pass. Extend the list rather than generalising it.</p>
     *
     * <p><strong>Applied to backtick-stripped text, and tolerant of the determiners it lists.</strong> An earlier version required the source type to be backticked and
     * accepted only the literal prefix {@code "its "}. Four plausible rewrites of the same legal
     * sentence defeated it — measured, all four: backticking the target
     * ({@code into `sub-packages`}) captured a leading backtick, {@code into its own sub-packages}
     * captured "own", {@code into a sub-package} captured "a" — three false alarms on correct copy
     * — and {@code `WorkPackage`s into their sub-packages} matched nothing at all, which is the
     * dangerous one: a genuine cross-type regression written with a plural type token would have
     * been skipped in silence.</p>
     */
    private static final Pattern NESTED_INTO = Pattern.compile(
            "([A-Za-z]+) into (?:all of its |one of its |its own |its |their |each |every |any "
            + "|some |both |a |an |the )?([A-Za-z-]+)");

    /** A prescribed value, as written in the prose: {@code `arrangement: "topology"`}. */
    private static final Pattern ARRANGEMENT = Pattern.compile("arrangement:\\s*\"([a-z-]+)\"");
    private static final Pattern DIRECTION = Pattern.compile("direction:\\s*\"([a-z-]+)\"");

    /**
     * An {@code AssignmentRelationship} clause: the type name up to the first em-dash.
     *
     * <p>The em-dash is where these bullets stop naming pairs and start explaining them, so it is
     * the clause boundary. Bounding it matters: without it the scan would run on into the
     * neighbouring {@code RealizationRelationship} clause and read {@code Artifact→
     * ApplicationComponent} — which is legal ({@code or}) — as an Assignment claim.</p>
     */
    private static final Pattern ASSIGNMENT_CLAUSE =
            Pattern.compile("AssignmentRelationship((?:(?!--)[^—–\n])*)");

    /**
     * A pair named inside such a clause: {@code Node→deployed-`ApplicationComponent`}.
     *
     * <p>The optional qualifier accepts either case. The copy that carried the original defect wrote
     * {@code deployed-}; a capitalised {@code Deployed-} would otherwise be captured <em>as the
     * target type</em>, yielding {@code Node→Deployed} and silently losing the violation.</p>
     */
    private static final Pattern NAMED_PAIR =
            Pattern.compile("([A-Z][A-Za-z]+)`?\\s*→\\s*`?(?:[Dd]eployed-)?`?([A-Z][A-Za-z]+)");

    /** Where a sentence stops prescribing and starts forbidding. See the known limits above. */
    private static final Pattern PROHIBITION =
            Pattern.compile("(?i)do\\s+\\*\\*not\\*\\*|do not use");

    /** A Java {@code List.of("a", "b")} enum declaration in the handler's tool schema. */
    private static final Pattern ENUM_LITERAL =
            Pattern.compile("List\\.of\\(\\s*((?:\"[A-Za-z_-]+\"\\s*,?\\s*)+)\\)");

    // ---- The swimlane contract --------------------------------------------------------------

    /**
     * The Business Process Cooperation delta must stack its lanes the way its own topology block
     * draws them.
     *
     * <p>The block's rule is that each role is a horizontal lane and the flow crosses lanes
     * <em>vertically</em> at hand-offs — lanes stacked top-to-bottom. Per the {@code arrange-groups}
     * schema, {@code direction: "horizontal"} arranges the groups left-to-right, which renders those
     * lanes as columns. The index tells the agent the topology block is the payload to match, so the
     * two cannot both be authoritative.</p>
     */
    @Test
    public void shouldStackSwimlanesTopToBottom_soTheStep7DeltaMatchesItsOwnTopologyBlock() {
        String section = section(read(BEHAVIOUR_PROCESS_FLOW), "Business Process Cooperation");
        String delta = deltaLine(section, "Step 7:");

        assertTrue("The swimlane topology block stacks its lanes top-to-bottom (\"the flow runs "
                + "left-to-right and crosses lanes vertically at hand-offs\"), but its Step 7 delta "
                + "does not prescribe direction: \"vertical\". arrange-groups renders "
                + "direction: \"horizontal\" as groups side by side — six role lanes as six "
                + "columns. Delta reads: " + delta,
                delta.contains("direction: \"vertical\""));

        assertFalse("The Step 7 delta still prescribes direction: \"horizontal\", which contradicts "
                + "the topology block above it. Delta reads: " + delta,
                delta.contains("direction: \"horizontal\""));

        assertTrue("The topology block no longer states that hand-offs cross lanes vertically, so "
                + "the assertion above is now guarding a rule the recipe does not make. Fix the "
                + "block or retire this pin — do not weaken it.",
                collapse(section).contains("crosses lanes vertically at hand-offs"));
    }

    /**
     * A production Javadoc cites this recipe as its evidence. Neither may move without the other.
     *
     * <p>{@code OrderedAxisRemedy} deliberately excludes a process-cooperation view from the
     * reorder-forbidding set, and grounds that exclusion in the recipe: the recommended build
     * sequence for that view class <em>prescribes the connectivity-driven group arrangement</em>,
     * so a connectivity re-layout re-derives the step order rather than scrambling it. The only
     * thing in the repo that prescribes it is this delta's {@code arrangement: "topology"}.</p>
     *
     * <p>Without this pin the two artifacts are joined by nothing. {@code OrderedAxisRemedyTest}
     * asserts the <em>predicate</em> — that the viewpoint is absent from the set — and would stay
     * green forever over a rationale the recipe had stopped supporting.</p>
     */
    @Test
    public void shouldBackTheOrderedAxisRationale_soAJavadocCannotOutliveTheRecipeItCites() {
        String rationale = "prescribes the connectivity-driven group arrangement";
        String remedy = collapse(stripJavadocMargin(readSource("OrderedAxisRemedy.java")));
        if (!remedy.contains(rationale)) {
            return; // The claim was withdrawn; there is nothing left to back.
        }

        String delta = deltaLine(
                section(read(BEHAVIOUR_PROCESS_FLOW), "Business Process Cooperation"), "Step 7:");

        assertTrue("OrderedAxisRemedy's Javadoc still says the recommended build sequence for a "
                + "process-cooperation view \"" + rationale + "\", and OrderedAxisRemedyTest pins "
                + "the exclusion that rests on it — but this recipe no longer prescribes "
                + "arrangement: \"topology\". One of the two is now false. Either restore the "
                + "arrangement, or withdraw the claim in OrderedAxisRemedy AND the rationale in "
                + "OrderedAxisRemedyTest in the same commit. Delta reads: " + delta,
                delta.contains("arrangement: \"topology\""));
    }

    /**
     * The swimlane Step 6 delta must say that {@code row} packs each lane on its own.
     *
     * <p>{@code GroupLayoutCalculator.computeRowLayout} starts at the container's own padding and
     * advances by each child's width, so a step's x is a function of the steps before it
     * <em>in that lane</em> and nothing else. Steps sharing an ordinal therefore do not line up
     * across lanes — and cross-lane alignment is what makes a swimlane readable, because it is what
     * turns a hand-off into a vertical drop. Prescribing {@code row} without saying so reads as an
     * endorsement of an arrangement that quietly loses the view's main affordance.</p>
     */
    @Test
    public void shouldWarnThatRowPacksEachLaneIndependently_soCrossLaneAlignmentIsNotAssumed() {
        String delta = collapse(deltaLine(
                section(read(BEHAVIOUR_PROCESS_FLOW), "Business Process Cooperation"), "Step 6:"));

        assertTrue("The Step 6 delta prescribes `layout-within-group` `row` but does not say that "
                + "it packs each lane INDEPENDENTLY. Delta reads: " + delta,
                delta.contains("independently"));

        assertTrue("The Step 6 delta must name the consequence — that steps do not align ACROSS "
                + "LANES — not just the mechanism. A reader who is not told the consequence has no "
                + "reason to act on the mechanism. Delta reads: " + delta,
                delta.contains("across lanes"));
    }

    // ---- The index's routing tables ---------------------------------------------------------

    /**
     * A flat, estate-wide application interaction view must be routed by Step 0.
     *
     * <p>Such a view — the whole estate, ungrouped, dense with app-to-app connections — fell
     * through <em>both</em> of the index's tables. Step 0's landscape row prescribes one Grouping
     * per domain, which is precisely what a flat estate-wide brief excludes; Step 2 then
     * keyword-matches "integrate" and hands over a hub-and-spoke topology with domain clusters.
     * Following that match produces the wrong shape, so the view has to be routed explicitly
     * rather than left to fall out by elimination.</p>
     */
    @Test
    public void shouldRouteTheFlatEstateWideInteractionView_soItDoesNotFallThroughToTheHubRecipe() {
        String row = collapse(rowContaining(section(read(INDEX), "Step 0"), "interaction",
                "a Step 0 row for the flat, estate-wide application interaction view"));

        assertTrue("The Step 0 interaction row must route to \"No recipe.\" — the hub-and-spoke "
                + "page is the wrong shape for a flat estate-wide view. Row reads: " + row,
                row.contains("No recipe"));

        assertTrue("The Step 0 interaction row must tell the agent NOT to create Groupings. That is "
                + "the operative difference from the landscape row directly above it, which "
                + "prescribes one Grouping per domain — without it the two rows are not "
                + "distinguishable by a reader who has only this page. Row reads: " + row,
                row.contains("not** create Grouping") || row.contains("not create Grouping"));
    }

    /**
     * The integration routing row must read as the narrow hub-centred slice.
     *
     * <p>It is the row a keyword match on "application" + "integrate" lands on, so it is the row
     * that has to say what it is <em>not</em>.</p>
     */
    @Test
    public void shouldNarrowTheIntegrationRoutingRow_soItReadsAsTheHubCentredSlice() {
        String row = collapse(rowContaining(section(read(INDEX), "Step 2"),
                "recipes/application-integration", "the Step 2 routing row for the hub recipe"));

        assertTrue("The Step 2 row that routes to the application-integration recipe must "
                + "distinguish the narrow hub-centred slice from the estate-wide interaction "
                + "picture, which is a Step 0 case. Without that, a keyword match on \"integrate\" "
                + "sends an estate-wide view to a hub-and-spoke topology. Row reads: " + row,
                row.contains("estate-wide"));
    }

    /**
     * The landscape row keeps its own case. Sharpening the contrast with the new interaction row
     * must not cost the inventory shape its routing.
     */
    @Test
    public void shouldKeepRoutingTheGroupedInventory_soTheNewRowDoesNotDisplaceIt() {
        String row = collapse(rowContaining(section(read(INDEX), "Step 0"), "landscape",
                "the Step 0 row for the grouped application landscape / inventory"));

        assertTrue("The landscape row must still route to \"No recipe.\" with the clustered-grid "
                + "instruction. Row reads: " + row,
                row.contains("No recipe") && row.contains("grid"));
    }

    /**
     * The integration page must wave off BOTH no-recipe application shapes, not just one.
     *
     * <p>Its opening parenthetical is the last line of defence for a reader who reached the page
     * by keyword match. It named only the grouped landscape inventory, which was complete while
     * the index routed one such shape and became half an answer the moment the index started
     * routing two. A page that turns away the inventory and keeps the interaction topology sends
     * the wrong shape into hub-and-spoke — the exact failure the index change exists to stop.</p>
     */
    @Test
    public void shouldWaveOffBothNoRecipeShapes_soTheIndexAndThePageAgreeOnHowManyThereAre() {
        String intro = collapse(read(APPLICATION_INTEGRATION).split("## ", 2)[0]);

        assertTrue("The integration page's intro must still wave off the grouped landscape "
                + "inventory. Intro reads: " + intro, intro.contains("landscape"));

        assertTrue("The index's Step 0 routes TWO no-recipe application shapes, but this page's "
                + "intro only knows the landscape one. A reader who reaches this page by keyword "
                + "match on an estate-wide interaction view is not turned away. Intro reads: "
                + intro,
                intro.contains("interaction"));
    }

    // ---- The reserved-lane promise ----------------------------------------------------------

    /**
     * The integration recipe must not promise auto-placement its own hub cannot receive.
     *
     * <p>{@code ArrangeGroupsStandaloneLane.isLaneEligibleType} admits only {@code INode},
     * {@code IDevice}, {@code IPath} and {@code ICommunicationNetwork}. This recipe's hub is an
     * ESB / API gateway / broker, and its element subset is {@code ApplicationComponent} — which
     * never qualifies. So "no manual reposition needed" was false for every canonical instance of
     * the page it appears on, while being true of the Technology page carrying the same sentence.
     * A qualified clause is not a defence when the qualifier excludes the whole worked example.</p>
     */
    @Test
    public void shouldDenyTheReservedLaneToAnApplicationComponentHub_soThePromiseIsConditional() {
        String delta = collapse(deltaLine(
                section(read(APPLICATION_INTEGRATION), "Application Cooperation View"), "Step 7:"));

        assertTrue("The integration recipe describes the reserved inter-group lane without naming "
                + "ApplicationComponent — the type its own hub actually is, and the one type the "
                + "lane predicate excludes. Delta reads: " + delta,
                delta.contains("ApplicationComponent"));

        assertFalse("The integration recipe still promises \"no manual `update-view-object` "
                + "reposition needed\". That is false for an ESB / API gateway / broker, which is "
                + "what this page's hub always is. Delta reads: " + delta,
                delta.contains("no manual `update-view-object` reposition needed"));
    }

    /**
     * The correction must not overshoot. The same sentence is TRUE on the Technology page, whose
     * hub is a {@code Path} / {@code Node} and does qualify — the divergence is at one seam, not
     * in the shared behaviour, so only one page may move.
     */
    @Test
    public void shouldKeepTheReservedLanePromiseWhereItHolds_soOnlyTheDivergingSeamMoves() {
        String delta = collapse(deltaLine(
                section(read(TECHNOLOGY_DEPLOYMENT), "Technology / Deployment View"), "Step 7:"));

        assertTrue("The Technology recipe's reserved-lane promise is correct for its own hub types "
                + "and must survive the integration recipe's correction. Delta reads: " + delta,
                delta.contains("reserved inter-zone lane"));
    }

    // ---- The Assignment contract ------------------------------------------------------------

    /**
     * No recipe may name an Assignment into an {@code ApplicationComponent}.
     *
     * <p>The Technology page listed three deployment Assignments — {@code Node→Artifact},
     * {@code Node→SystemSoftware}, {@code Node→deployed-ApplicationComponent}. The first two are
     * legal ({@code aio}, {@code cfgiortv}); the third is not, and neither is any other pair with
     * that target (see {@link #NEVER_AN_ASSIGNMENT_TARGET}). An agent that acted on the sentence
     * got {@code RELATIONSHIP_NOT_ALLOWED} back from {@code create-relationship} with nothing to
     * tell it the guidance itself was wrong.</p>
     *
     * <p>Asserted as the general invariant rather than against the one bad string on purpose: a
     * rewrite to {@code Device→ApplicationComponent} would satisfy a string-level pin and stay
     * exactly as illegal.</p>
     *
     * <p><strong>General over the pairs it can see, not over all prose.</strong> The method name
     * says "named pair" rather than "recipe" because that is the honest reach: a violation written
     * as free prose, an ASCII {@code ->} or a reversed arrow is invisible here and is held by the
     * targeted assertions instead. See the known-limits list on the class.</p>
     */
    @Test
    public void shouldNeverAssignIntoAnApplicationComponent_soNoNamedPairPrescribesARejectedCall() {
        Set<String> illegal = new TreeSet<>();

        for (AssignmentPair pair : assignmentPairs()) {
            if (NEVER_AN_ASSIGNMENT_TARGET.equals(pair.target())) {
                illegal.add(pair.toString());
            }
        }

        assertTrue("These recipe pages name an AssignmentRelationship targeting an "
                + NEVER_AN_ASSIGNMENT_TARGET + ", which Archi's own relationship matrix permits "
                + "from NO source concept — so an agent following them verbatim gets "
                + "RELATIONSHIP_NOT_ALLOWED. Where an app-to-infra link genuinely has to be drawn, "
                + "the legal one is RealizationRelationship Artifact→ApplicationComponent. "
                + "Offending pairs: " + illegal, illegal.isEmpty());
    }

    /** The count pin. Without it a scanner matching nothing would pass over an empty set. */
    @Test
    public void shouldFindEveryAssignmentPair_soAnEmptyAssignmentScanCannotPassAsClean() {
        List<AssignmentPair> found = assignmentPairs();

        assertEquals("Assignment-pair count changed. Either you added or removed a named pair in an "
                + "AssignmentRelationship clause — update EXPECTED_ASSIGNMENT_PAIRS in the same "
                + "commit — or the scanner has regressed and is no longer reading the copy it "
                + "guards; note the clause boundary is the em-dash, so losing one makes a clause "
                + "run on into its neighbour. A THIRD case never reaches this message at all: a "
                + "violation written in a form NAMED_PAIR does not match contributes nothing and "
                + "leaves the count unchanged. Found: " + found,
                EXPECTED_ASSIGNMENT_PAIRS, found.size());
    }

    /**
     * The correction is subtractive. Deleting the bullet would also remove the illegal pair.
     *
     * <p>Two of the three pairs were right, and they carry the recipe's actual instruction — that
     * deployment is conveyed by containment and the Assignment is never drawn. A fix that took the
     * whole clause out would pass the invariant above while losing the guidance.</p>
     */
    @Test
    public void shouldKeepTheLegalDeploymentAssignments_soTheCorrectionCannotOvershoot() {
        String delta = collapse(deltaLine(
                section(read(TECHNOLOGY_DEPLOYMENT), "Technology / Deployment View"),
                "Imply by nesting"));

        assertTrue("The Technology recipe no longer names the legal deployment Assignment "
                + "Node→Artifact. It is legal (matrix: aio) and load-bearing — the deployed member "
                + "is nested rather than wired precisely because the Assignment is implied.",
                delta.contains("Node→Artifact"));

        assertTrue("The Technology recipe no longer names Node→SystemSoftware, which is legal "
                + "(matrix: cfgiortv) and carries the same instruction.",
                delta.contains("Node→SystemSoftware"));
    }

    /**
     * The topology block must not re-assert, two paragraphs later, what the subset just corrected.
     *
     * <p>Its Rule paragraph told the reader that SystemSoftware, Artifacts <em>and deployed
     * ApplicationComponents</em> are nested "never by a drawn Assignment" — one unqualified claim
     * covering all three members, of which only two have an Assignment to leave undrawn. The claim
     * straddles a line break inside the fenced block, which is why a line-oriented grep scoped the
     * original fix to a single site.</p>
     */
    @Test
    public void shouldNotImplyAnAssignmentForADeployedComponent_soTheRuleAgreesWithItsOwnSubset() {
        String rule = collapse(fencedBlock(
                section(read(TECHNOLOGY_DEPLOYMENT), "Technology / Deployment View"),
                "the Technology page's topology block"));

        assertTrue("The topology block no longer carries the container Rule, so the assertions "
                + "below are guarding prose that has moved. Re-point them — do not delete them.",
                rule.contains("each infrastructure NODE is a container box"));

        assertTrue("The topology Rule must scope the implied-and-never-drawn Assignment to the "
                + "members that actually have one — an Artifact or SystemSoftware. Left "
                + "unqualified it covers the deployed ApplicationComponent too, which is the claim "
                + "the relationship subset above it no longer makes.",
                rule.contains("Assignment to an Artifact or SystemSoftware is implied"));

        assertTrue("The topology Rule must say that a deployed ApplicationComponent has no "
                + "Assignment to imply. Nesting it stays correct — add-to-view neither creates nor "
                + "validates a relationship — but there is no implied Assignment behind the "
                + "containment, and the Rule is where a reader learns what the nesting means.",
                rule.contains("has none to imply"));
    }

    /**
     * The integration recipe must not name an ownership Assignment onto its own hub.
     *
     * <p>That page's hub is an {@code ApplicationComponent} — its element subset says so — so both
     * the subset's "their Assignment to the hub" and the relationship subset's
     * "{@code AssignmentRelationship} from a collaboration/team to a component" describe a model
     * Archi cannot hold. The real link is an Aggregation from the owning
     * {@code ApplicationCollaboration} ({@code fgortv}), or an Association from a business team
     * ({@code fotv}).</p>
     *
     * <p>Prose, not arrow-form, so the scan above cannot see it; this is one of the targeted
     * assertions the class Javadoc's known-limits list refers to.</p>
     */
    @Test
    public void shouldNotNameAnOwnershipAssignmentOntoTheHub_soThePageDescribesAModelThatCanExist() {
        String section = collapse(
                section(read(APPLICATION_INTEGRATION), "Application Cooperation View"));

        assertTrue("The integration recipe no longer tells the agent to leave the owning "
                + "collaboration off the view, so the assertions below are guarding prose that has "
                + "moved. Re-point them — do not delete them.",
                section.contains("Always omit"));

        assertFalse("The integration recipe still names an Assignment from a collaboration or team "
                + "onto the hub. The hub is an ApplicationComponent, into which no concept may "
                + "Assign — so the page describes a model that cannot exist, and an agent that "
                + "later tries to draw the ownership link it names gets RELATIONSHIP_NOT_ALLOWED. "
                + "An owning ApplicationCollaboration AGGREGATES the component (fgortv); a "
                + "business team gets an Association (fotv).",
                section.contains("Assignment to the hub")
                        || section.contains("`Assignment` from a team/collaboration")
                        || section.contains("AssignmentRelationship` from a collaboration/team")
                        || section.contains("do not draw the Assignment"));
    }

    /**
     * The Step 4 filter delta must exclude the relationship the page actually names.
     *
     * <p>It read "Composition/Assignment excluded" — correct only while the page's own relationship
     * subset named an Assignment onto the hub. Once that became the Aggregation it always was, the
     * delta was left pointing at a relationship the page no longer mentions, telling the agent to
     * keep out of its {@code relationshipTypes} filter a type that was never going to be in it,
     * and saying nothing about the one that is.</p>
     *
     * <p>The same sentence on {@code technology-deployment.md} still says "Assignment/Composition
     * excluded" and is <em>correct</em> there — that page does name two legal deployment
     * Assignments. One seam moves, not the shared wording.</p>
     */
    @Test
    public void shouldExcludeTheRelationshipThePageNames_soStep4DoesNotFilterAPhantomType() {
        String delta = collapse(deltaLine(
                section(read(APPLICATION_INTEGRATION), "Application Cooperation View"), "Step 4:"));

        assertTrue("The integration recipe's Step 4 filter delta does not name Aggregation, which "
                + "is the relationship its own subset says is conveyed by nesting. Delta reads: "
                + delta, delta.contains("Aggregation"));

        assertFalse("The integration recipe's Step 4 delta still excludes an Assignment. This page "
                + "names no Assignment anywhere — the ownership link onto an ApplicationComponent "
                + "hub is an Aggregation, because no concept may Assign into one. Delta reads: "
                + delta, delta.contains("Assignment excluded") || delta.contains("/Assignment"));
    }

    /**
     * The general rule the recipes are instances of must not imply an Assignment that cannot exist.
     *
     * <p>Best-practice rule 4 said an actor/role/collaboration "owns or performs" an element, with
     * one parenthetical — {@code AssignmentRelationship} from the team/role/collaboration to
     * <em>the element</em> — covering both verbs and every target type. "Performs" is right:
     * {@code BusinessRole→BusinessProcess} is {@code fiotv}. "Owns" is not, whenever the element is
     * an {@code ApplicationComponent}: {@code BusinessActor} and {@code BusinessRole} are
     * {@code fotv} and {@code ApplicationCollaboration} is {@code fgortv} — no {@code i} anywhere.
     * The real ownership link is the Aggregation those {@code g} letters carry.</p>
     *
     * <p>This is the page the integration recipe's own wrong instance came from, and it is cited by
     * name from {@code recipes/index.md} and {@code reference/archimate-layers.md}. Fixing the
     * instances while leaving the general rule standing invites the next instance to be re-derived
     * from it. No arrow scan can reach it — the rule says "the element", never naming a type — so
     * it takes a targeted assertion.</p>
     */
    @Test
    public void shouldNotImplyOwnershipIsAnAssignment_soTheGeneralRuleCannotReseedTheInstances() {
        String rule = collapse(read(firstResolvable(RESOURCE_ROOTS)
                .resolve("reference").resolve("archimate-view-patterns.md")));

        assertTrue("Best-practice rule 4 no longer tells the reader to nest a part rather than draw "
                + "the structural relationship, so this pin is guarding prose that has moved. "
                + "Re-point it — do not delete it.",
                rule.contains("Nest a part inside its parent"));

        assertFalse("Best-practice rule 4 still offers one AssignmentRelationship parenthetical for "
                + "both \"owns\" and \"performs\". Performing behaviour IS an Assignment; OWNING an "
                + "ApplicationComponent can never be one, because no concept may Assign into it — "
                + "the ownership link is an Aggregation. This is the general rule the integration "
                + "recipe's own wrong instance was derived from, so leaving it unqualified lets the "
                + "same defect be re-derived.",
                rule.contains("owns or performs it (`AssignmentRelationship`"));

        assertTrue("Rule 4 must name the Aggregation as the ownership link, otherwise it corrects "
                + "the false claim without supplying the true one and a reader has nothing to use.",
                rule.contains("owning `ApplicationCollaboration`"));

        assertTrue("Rule 4 must say what a business actor or role gets instead. Aggregation is the "
                + "ownership link for an ApplicationCollaboration ONLY: BusinessActor, BusinessRole "
                + "and even BusinessCollaboration are all fotv toward an ApplicationComponent — no "
                + "i AND no g. Naming only the Aggregation invites a reader holding an actor to "
                + "reach for it, which is the same defect one relationship over.",
                rule.contains("AssociationRelationship`, not an Aggregation"));
    }

    // ---- The roadmap part-of contract --------------------------------------------------------

    /**
     * The two implementation-layer types the roadmap page may never link by a part-of relationship.
     *
     * <p><strong>Derived, not asserted.</strong> The server's single validity gate is
     * {@code ArchimateModelUtils.isValidRelationship}, backed by {@code RelationshipsMatrix}, which
     * loads {@code model/relationships.xml} out of the {@code com.archimatetool.model} bundle. That
     * file was read from two independent copies, which agree byte-for-byte on these rows:</p>
     *
     * <ul>
     *   <li>{@code com.archimatetool.model/model/relationships.xml} at the {@code release_5_10_0}
     *       tag of the Archi source tree — {@code <relationships version="3.2">};</li>
     *   <li>{@code com.archimatetool.model_5.6.0.202505120659/model/relationships.xml} inside the
     *       installed application bundle — also {@code <relationships version="3.2">}.</li>
     * </ul>
     *
     * <p>Both give {@code WorkPackage → ImplementationEvent = fot} <em>and</em>
     * {@code ImplementationEvent → WorkPackage = fot}. Key letters, from the sibling
     * {@code model/relationships-keys.xml}: {@code a} Access, {@code c} Composition, {@code f}
     * Flow, {@code g} Aggregation, {@code i} Assignment, {@code n} Influence, {@code o}
     * Association, {@code r} Realization, {@code s} Specialization, {@code t} Triggering,
     * {@code v} Serving. So there is no {@code g} and no {@code c} in either direction — neither
     * Aggregation nor Composition is permitted between these two types, whichever way round — and
     * the {@code t} that is present makes Triggering the legal link.</p>
     *
     * <p><strong>Measured footnote, so nobody re-derives it wrongly.</strong>
     * {@code RelationshipsMatrix} reads a lower-case letter as a <em>derived</em> relationship and
     * an upper-case one as direct, which invites the reading that a lower-case {@code t} is somehow
     * weaker. It is not: all 3844 {@code relations} attributes in the shipped file are lower-case
     * (measured, both copies), and {@code isValidRelationship} tests {@code containsKey}, so the
     * direct/derived split is inert for validity. A lower-case {@code t} is a permitted Triggering,
     * full stop.</p>
     *
     * <p><strong>Why the matrix is not read from this test.</strong> It lives outside the repo,
     * under an install path that differs between a developer machine and CI, so a test that could
     * not find it would skip — and a skipped test is a green scan, which is the failure mode this
     * whole class exists to avoid. Same reasoning as {@link #NEVER_AN_ASSIGNMENT_TARGET}.</p>
     */
    private static final String NEVER_PART_OF_A_WORK_PACKAGE = "ImplementationEvent";

    /**
     * The roadmap page must not describe an implementation event as part of a work package.
     *
     * <p>The relationship subset prescribed an {@code AggregationRelationship} of
     * {@code ImplementationEvent}s into a work package, under the heading that means "this link
     * exists in the model, just do not draw it". The matrix permits no such link in either
     * direction (see {@link #NEVER_PART_OF_A_WORK_PACKAGE}), so an agent that built the model the
     * page prescribed got {@code create-relationship} rejected, with nothing to tell it the
     * guidance was wrong.</p>
     *
     * <p><strong>Why this asserts the pair and not the token.</strong> Banning the word
     * {@code AggregationRelationship} outright across the page would be over-reach in the direction
     * that costs real guidance: {@code WorkPackage → WorkPackage} is {@code cfgost} and
     * {@code Deliverable → Deliverable} is {@code cgos}, so both carry a {@code g} and a legal
     * Aggregation between same-type peers could be added tomorrow. What can never be true is a
     * part-of claim <em>naming the event</em>, so the assertion is scoped to the clause that names
     * it.</p>
     *
     * <p><strong>Targeted, deliberately.</strong> The corpus-wide {@link #NAMED_PAIR} scan reads
     * arrow-form pairs inside an {@code AssignmentRelationship} clause; this defect was written as
     * free prose with no arrow, so no arrow scan could see it — the class's own documented known
     * limit, which says such sites are held by targeted assertions instead. Generalising it would
     * mean a relationship-pair parser over the whole freeform corpus, and that is separately filed
     * work whose stated prerequisite is a front-matter redesign of these pages so the pairs are
     * data rather than prose. It is deliberately not started here.</p>
     */
    @Test
    public void shouldNotMakeAnEventPartOfAWorkPackage_soTheSubsetNamesOnlyALegalPair() {
        String subset = collapse(nestedBullets(
                section(read(ROADMAP_MIGRATION), "Roadmap / Migration View"),
                "**Relationship subset:**"));

        assertTrue("The roadmap page no longer carries the nesting bullet in its relationship "
                + "subset, so the assertions below are guarding prose that has moved. Re-point "
                + "them — do not delete them.",
                subset.contains("Imply by nesting"));

        assertTrue("The roadmap page's relationship subset no longer mentions "
                + NEVER_PART_OF_A_WORK_PACKAGE + " at all, so the assertions below are guarding "
                + "prose that has moved. Re-point them — do not delete them. (An absence pin alone "
                + "would pass happily over a page that had simply dropped the subject.)",
                subset.contains(NEVER_PART_OF_A_WORK_PACKAGE));

        assertTrue("The roadmap page's relationship subset no longer denies the part-of link in the "
                + "words the guard below reads (\"" + PART_OF_DENIAL + "\"). Re-point the guard — "
                + "do not delete it. This anchor is what makes a broad part-of scan safe: the scan "
                + "treats a sentence carrying this phrase as a denial rather than a claim, so if "
                + "the phrase is reworded the scan must fail loudly rather than quietly narrow.",
                subset.contains(PART_OF_DENIAL));

        assertFalse("The roadmap page again names a part-of relationship for an "
                + NEVER_PART_OF_A_WORK_PACKAGE + " and a WorkPackage. Archi's own matrix has both "
                + "directions at fot — Flow, Association, Triggering — with NO g and NO c, so "
                + "neither Aggregation nor Composition can be created between them and an agent "
                + "acting on the sentence gets create-relationship rejected. The legal link is "
                + "TriggeringRelationship (event → package): the deadline paces the package. "
                + "Subset reads: " + subset,
                partOfClaimForTheEvent(subset));

        assertTrue("The roadmap page must name the legal link, not merely stop naming the illegal "
                + "one. Deleting the clause outright would leave " + NEVER_PART_OF_A_WORK_PACKAGE
                + " named in the element subset and nested by Step 3 with no statement of how it "
                + "relates to anything — a false claim traded for a silence, on a page whose whole "
                + "job is to tell an agent what to create.",
                subset.contains("`TriggeringRelationship` (`ImplementationEvent` → `WorkPackage`)"));

        assertTrue("The roadmap page names the Triggering but no longer says the nesting is a "
                + "LAYOUT CHOICE. That distinction is the whole correction: leaving the event in a "
                + "bucket meaning \"containment conveys this relationship\" would re-make the same "
                + "class of false claim one type over, because containment conveys part-of and not "
                + "\"paces\" — a notation ArchiMate does not define.",
                subset.contains(LAYOUT_CHOICE_NOT_A_RELATIONSHIP));
    }

    /**
     * The build steps must not restate, two paragraphs later, what the subset just corrected.
     *
     * <p>Step 3 told the agent to nest "aggregated {@code ImplementationEvent}s" — the subset's
     * claim in the imperative mood, inside an instruction an agent executes. Correcting the subset
     * alone would ship a fixed relationship list beside an unfixed build step, which is the precise
     * self-contradiction this class exists to prevent. The same shape as
     * {@link #shouldNotImplyAnAssignmentForADeployedComponent_soTheRuleAgreesWithItsOwnSubset}.</p>
     */
    @Test
    public void shouldNotCallTheNestedEventsAggregated_soStep3AgreesWithItsOwnSubset() {
        String delta = collapse(deltaLine(
                section(read(ROADMAP_MIGRATION), "Roadmap / Migration View"), "Step 3:"));

        assertTrue("The roadmap page's Step 3 no longer prescribes nesting via "
                + "parentViewObjectId, so the assertions below are guarding prose that has moved. "
                + "Re-point them — do not delete them.",
                delta.contains("parentViewObjectId"));

        assertFalse("Step 3 again describes the nested " + NEVER_PART_OF_A_WORK_PACKAGE
                + "s as aggregated. Nesting them is fine — add-to-view neither creates nor "
                + "validates a relationship — but there is no Aggregation behind the containment "
                + "to describe, and a build step is where an agent learns what the nesting means. "
                + "Delta reads: " + delta,
                partOfClaimForTheEvent(delta));

        assertTrue("Step 3 must say what the event nesting IS, having stopped saying what it is "
                + "not. Left bare, the reader is invited to supply the part-of reading the subset "
                + "just removed. Note this is the SAME phrase the relationship subset uses, "
                + "deliberately: an earlier draft said the same thing two different ways at the two "
                + "sites, so unifying the wording — a harmless-looking consistency edit, and better "
                + "prose for an agent reading both — would have broken whichever pin it did not "
                + "match. Identical claim, identical words, one assertion string. Delta reads: "
                + delta,
                delta.contains(LAYOUT_CHOICE_NOT_A_RELATIONSHIP));
    }

    /**
     * The Step 4 filter delta must exclude only types the page actually names.
     *
     * <p>It read "Composition/Aggregation excluded" — correct only while the subset above it named
     * an Aggregation. The moment that claim was removed, the parenthetical was left telling the
     * agent to keep out of its {@code relationshipTypes} filter a type the page no longer mentions.
     * That is the same defect as
     * {@link #shouldExcludeTheRelationshipThePageNames_soStep4DoesNotFilterAPhantomType} one recipe
     * over, and it is <em>created by</em> the fix rather than merely surviving it — which is why
     * the correction could not stop at the subset.</p>
     *
     * <p>The {@code relationshipTypes} array literal itself does not move: Association and
     * Realization are what this view draws, before and after.</p>
     */
    @Test
    public void shouldExcludeOnlyTypesTheRoadmapNames_soStep4DoesNotFilterAPhantomType() {
        String delta = collapse(deltaLine(
                section(read(ROADMAP_MIGRATION), "Roadmap / Migration View"), "Step 4:"));

        assertTrue("The roadmap page's Step 4 no longer carries its relationshipTypes filter, so "
                + "the assertions below are guarding prose that has moved. Re-point them — do not "
                + "delete them.",
                delta.contains("relationshipTypes: [\"AssociationRelationship\", "
                        + "\"RealizationRelationship\"]"));

        assertFalse("Step 4 again excludes an Aggregation. This page names no Aggregation "
                + "anywhere — the event-to-package link is a Triggering, because no part-of "
                + "relationship exists between those types in either direction — so the "
                + "parenthetical names a phantom: it tells the agent to keep out of the filter a "
                + "type that was never going to be in it, and says nothing about the one that is. "
                + "Delta reads: " + delta,
                delta.contains("Aggregation"));

        int conveyed = delta.indexOf("conveyed by nesting");
        assertFalse("Step 4 has folded the event Triggering into the \"conveyed by nesting\" "
                + "clause. Those two exclusions are excluded for DIFFERENT reasons and must stay "
                + "apart: the Composition is left out because containment states it, while the "
                + "Triggering is left out only because a connector between a box and the box it "
                + "sits in is unreadable. Merging them re-asserts, in the filter delta, the very "
                + "claim the relationship subset removed — that nesting conveys the event link. "
                + "Delta reads: " + delta,
                conveyed >= 0 && delta.substring(0, conveyed).contains("Triggering"));

        assertTrue("Step 4 must still exclude the Composition, which the page DOES name — the "
                + "sub-package and sub-deliverable nesting in the relationship subset. Dropping it "
                + "would fix the phantom by creating a silence. Delta reads: " + delta,
                delta.contains("Composition excluded"));
    }

    /**
     * The correction is subtractive, and the clause it subtracts from carries legal guidance too.
     *
     * <p>The nesting bullet named two relationships. Only the Aggregation was wrong; the
     * Composition half is legal in both of the pairs it scopes itself to —
     * {@code WorkPackage → WorkPackage} is {@code cfgost} and {@code Deliverable → Deliverable} is
     * {@code cgos} — and it carries the page's actual instruction, that a part is nested rather
     * than wired. A fix that took the whole bullet out would satisfy the invariant above while
     * losing real guidance. Same trap, same shape, as
     * {@link #shouldKeepTheLegalDeploymentAssignments_soTheCorrectionCannotOvershoot}.</p>
     *
     * <p><strong>And the overshoot has a second direction.</strong> The Composition is correct
     * <em>because</em> it scopes itself to same-type nesting. A loosening rewrite —
     * "Composition of a {@code WorkPackage} into its {@code Deliverable}s" — would introduce a
     * second illegal pair while fixing the first: {@code WorkPackage → Deliverable} is {@code aor},
     * so the legal structural link there is Realization and Composition is not permitted. The last
     * assertion holds that end.</p>
     */
    @Test
    public void shouldKeepTheLegalNestingCompositions_soTheCorrectionCannotOvershoot() {
        String delta = collapse(deltaLine(
                section(read(ROADMAP_MIGRATION), "Roadmap / Migration View"), "Imply by nesting"));

        assertTrue("The nesting bullet no longer tells the reader to nest the part, so the "
                + "assertions below are guarding prose that has moved. Re-point them — do not "
                + "delete them. (Without an anchor of its own this pin would borrow its "
                + "prose-has-moved signal from a sibling test reading a different slice.)",
                delta.contains("nest the part"));



        List<Nesting> nested = nestingsIn(COMPOSITION_CLAUSE, delta);

        assertEquals("The roadmap nesting bullet must nest exactly WorkPackage and Deliverable — "
                + "no fewer, and no more. Losing one drops guidance that is legal and load-bearing "
                + "(WorkPackage→WorkPackage is cfgost, Deliverable→Deliverable is cgos): the part "
                + "is nested rather than wired precisely because the Composition is implied. "
                + "Gaining one puts a type outside this recipe's scope into a prescription nothing "
                + "else checks — a self-consistent extra nesting is invisible to a presence check "
                + "and legal in the matrix, so this equality is the only thing that sees it. "
                + "Parsed nestings: " + nested,
                Set.of("WorkPackage", "Deliverable"), nestingSources(nested));

        Set<String> crossType = crossTypeNestings(nested);

        assertTrue("The nesting bullet names a Composition into something other than a sub-part of "
                + "the SAME type. It is legal only because it scopes itself that way: "
                + "WorkPackage→WorkPackage is cfgost and Deliverable→Deliverable is cgos, but "
                + "WorkPackage→Deliverable is aor — Access, Association, Realization, with NO c. So "
                + "a loosening rewrite such as \"Composition of a WorkPackage into its "
                + "Deliverables\" would introduce a second illegal pair while fixing the first, "
                + "and the legal structural link there is Realization. Offending clauses: "
                + crossType + ". Delta reads: " + delta,
                crossType.isEmpty());
    }

    /** A part-of relationship, in every spelling this corpus's prose actually uses. */
    private static final Pattern PART_OF_WORD = Pattern.compile("(?i)aggregat|compos");

    /**
     * What the page must call the event nesting, at every site that prescribes it.
     *
     * <p>Single-sourced on purpose. The subset bullet and the Step 3 delta make the same claim
     * about the same nesting, and an earlier draft worded it differently at the two sites; a future
     * editor unifying them — the obvious cleanup, and the better copy — would have satisfied one
     * pin's literal string and broken the other's, with nothing in either test to show the
     * divergence was intentional. One claim, one phrase, one constant.</p>
     */
    private static final String LAYOUT_CHOICE_NOT_A_RELATIONSHIP =
            "layout choice, not an implied relationship";

    /** Where a sentence stops asserting a part-of link and starts denying one. */
    private static final String PART_OF_DENIAL = "is **not** part of";

    /**
     * Is this text making a part-of claim about the implementation event?
     *
     * <p>Scoped by <em>sentence</em>, not by page or bullet. The page must be free to say the words
     * "Composition" and "Aggregation" twice over for entirely correct reasons — the neighbouring
     * bullet prescribes a legal Composition between same-type peers, and the event's own bullet
     * names the two part-of links in order to <strong>deny</strong> them. Neither is a claim about
     * the event, and a scan that cannot tell them apart is either blind or noisy.</p>
     *
     * <p><strong>An earlier version matched four literal strings and was both.</strong> Measured,
     * three regressions walked straight past it: a Composition restatement in the page's own plural
     * style ({@code `CompositionRelationship` of `ImplementationEvent`s into a `WorkPackage`} — the
     * literal it looked for required the singular "of an"), a bare {@code Aggregation} with the
     * {@code Relationship} suffix dropped, and the gerund "aggregating". All three re-make exactly
     * the claim this pin exists to forbid. In the other direction it fired on correct copy:
     * backticking the type names inside the denial — a strictly more consistent edit, since every
     * other type name on the page is backticked — tripped it. The literal-string approach was
     * wrong in both directions at once, which is the signature of pinning a phrase instead of the
     * claim it makes.</p>
     *
     * <p><strong>Stated reach, rather than implied generality.</strong> A sentence naming the event
     * is read as a denial when it carries {@link #PART_OF_DENIAL}, and the pins anchor on that
     * phrase being present, so rewording the denial fails loudly and tells the next reader to
     * re-point rather than passing in silence. What this deliberately does <em>not</em> attempt is
     * natural-language negation: a sentence that denied the part-of link and then prescribed one
     * anyway would be read as a denial. That is not a shape prose in this corpus takes, and
     * guessing at it would cost more in false alarms than it could buy.</p>
     */
    private static boolean partOfClaimForTheEvent(String text) {
        for (String sentence : text.split("(?<=\\.)\\s+")) {
            if (!sentence.contains(NEVER_PART_OF_A_WORK_PACKAGE)
                    || sentence.contains(PART_OF_DENIAL)) {
                continue;
            }
            if (PART_OF_WORD.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- The journey view's nesting bullet ---------------------------------------------------

    private static final String JOURNEY_VIEW = "Service Design / Customer Journey View";

    /**
     * The one nesting bullet in a view's relationship subset.
     *
     * <p>Scoped to the <em>bullet</em> rather than to the subset that contains it, via
     * {@link #deltaLine}, which asserts exactly one match. {@link #nestedBullets} returns every
     * bullet nested under the subset heading — the {@code Draw:} bullet as well as this one,
     * because neither is a top-level {@code "- "} line — so a scan built on it would read two
     * bullets while its failure messages named one. That is not merely imprecise wording: an edit
     * that moved a relationship from "imply by nesting" into "Draw" would change the instruction
     * from <em>containment states this</em> to <em>draw a connector for this</em>, and a guard
     * spanning both would not notice.</p>
     */
    private static final String NESTING_BULLET = "Imply by nesting";

    /**
     * A nesting clause must name the pair it prescribes, because most readings of it are rejected.
     *
     * <p>The bullet read "{@code CompositionRelationship} / {@code AssignmentRelationship} within
     * the support layers" and named no element types at all. That is not a mild vagueness. The
     * view's own element subset is {@code BusinessService}, {@code ApplicationService},
     * {@code ApplicationComponent} and {@code BusinessProcess}, and of the sixteen orderings those
     * four admit, exactly <em>one</em> permits an Assignment: {@code ApplicationComponent →
     * ApplicationService}, matrix {@code fiortv}. The other fifteen are rejected — including all
     * four same-type orderings, because none of these concepts may be assigned to itself. An agent
     * told to nest "an Assignment within the support layers" had one chance in sixteen of choosing
     * a pair {@code create-relationship} accepts.</p>
     *
     * <p>Sixteen counts the page's whole element subset rather than the three types the phrase
     * "support layers" literally scopes to — the wider and more conservative set, since a page
     * listing four types gives an agent no reliable way to exclude one. Over the literal scope it
     * is eight of nine. Both are stated in the shipped audit; the figure is recorded here so a
     * reader of this pin does not have to guess which set it was computed over.</p>
     *
     * <p>This is the failure mode a pair-by-pair audit cannot see, and the reason the shipped audit
     * classifies clauses by how many endpoint readings they admit rather than listing named pairs:
     * every pair the corpus named in full was already legal, while the clauses that named none were
     * where the rejections lived.</p>
     *
     * <p>Deliberately not generalised. Recognising this shape anywhere in the corpus would mean a
     * relationship-pair parser over freeform English, which is separately filed work whose stated
     * prerequisite is giving these pages structured front-matter so the pairs are data rather than
     * prose. A parser over the corpus as it stands fails by silently matching nothing and reporting
     * green, which launders an unchecked corpus as a checked one.</p>
     */
    @Test
    public void shouldNameTheOneLegalAssignment_soTheJourneyNestingIsNotReadIntoARejectedCall() {
        String subset = collapse(deltaLine(
                section(read(BEHAVIOUR_PROCESS_FLOW), JOURNEY_VIEW), NESTING_BULLET));

        assertTrue("The journey view's nesting bullet no longer mentions an "
                + "AssignmentRelationship at all, so the assertions below are guarding prose that "
                + "has moved. Re-point them — do not delete them. (An absence pin alone would pass "
                + "happily over a bullet that had simply dropped the subject.)",
                subset.contains("AssignmentRelationship"));

        List<AssignmentPair> named = pairsIn(subset);

        assertEquals("The journey view's Assignment clause must name exactly one pair. Naming none "
                + "is the defect this pin exists for — the clause then covers sixteen readings of "
                + "which fifteen are rejected. Naming more than one means a second pair arrived "
                + "without being checked against the matrix. Found: " + named,
                1, named.size());

        assertEquals("The journey view's Assignment clause names a source other than "
                + "ApplicationComponent. Among the four types this view uses, an Assignment is "
                + "permitted from ApplicationComponent and from nothing else: BusinessService and "
                + "ApplicationService are fotv and fortv toward their peers, BusinessProcess is "
                + "fotv, and no concept here may be assigned to itself. Found: " + named,
                "ApplicationComponent", named.get(0).source());

        assertEquals("The journey view's Assignment clause names a target other than "
                + "ApplicationService. ApplicationComponent→ApplicationService is fiortv and is the "
                + "only permitted Assignment among these four types; ApplicationComponent toward "
                + "BusinessService or BusinessProcess is fortv, with no i. Found: " + named,
                "ApplicationService", named.get(0).target());
    }

    /**
     * The journey view's Composition guidance must stay scoped to same-type nesting.
     *
     * <p>The same shape as {@link #shouldKeepTheLegalNestingCompositions_soTheCorrectionCannotOvershoot}
     * one recipe over, and live for the same reason: what makes a nesting Composition legal is not
     * that the two types are adjacent in a sentence but that each clause nests a type into a
     * sub-part of its <em>own</em> type. Among the four types this view uses there is
     * <strong>no</strong> permitted cross-type Composition at all, so the most natural loosening —
     * "a component is composed of its services" — would introduce an impermissible pair while
     * tidying the prose: {@code ApplicationComponent → ApplicationService} is {@code fiortv}, with
     * no {@code c}. The legal link between those two is the Assignment the sibling pin holds.</p>
     */
    @Test
    public void shouldKeepTheJourneyCompositionsSameType_soTheCorrectionCannotOvershoot() {
        String subset = collapse(deltaLine(
                section(read(BEHAVIOUR_PROCESS_FLOW), JOURNEY_VIEW), NESTING_BULLET));

        assertTrue("The journey view's nesting bullet no longer tells the reader to nest the part, "
                + "so the assertions below are guarding prose that has moved. Re-point them — do "
                + "not delete them.",
                subset.contains("nest the part"));

        List<Nesting> nested = nestingsIn(COMPOSITION_CLAUSE, subset);

        assertEquals("The journey nesting bullet must nest exactly BusinessService and "
                + "ApplicationComponent — no fewer, no more. Both Compositions are permitted "
                + "(cfgostv and cfgorstv) and load-bearing. An extra self-consistent nesting for "
                + "some other type would pass a presence check and be legal in the matrix while "
                + "still being outside this view's element subset. Parsed nestings: " + nested,
                Set.of("BusinessService", "ApplicationComponent"), nestingSources(nested));

        assertTrue("The journey view's nesting bullet names a Composition into something other "
                + "than a sub-part of the SAME type. It is legal only because it scopes itself that "
                + "way: among BusinessService, ApplicationService, ApplicationComponent and "
                + "BusinessProcess, the matrix permits Composition ONLY between a type and itself — "
                + "there is no permitted cross-type Composition among the four. In particular "
                + "ApplicationComponent→ApplicationService is fiortv, so \"a component composed of "
                + "its services\" would prescribe a rejected call, and the legal link there is the "
                + "Assignment named in the same bullet. Offending clauses: "
                + crossTypeNestings(nested) + ". Subset reads: " + subset,
                crossTypeNestings(nested).isEmpty());
    }

    // ---- The motivation view's nesting bullet ------------------------------------------------

    private static final String MOTIVATION_VIEW = "Motivation View";

    /**
     * Motivation Aggregation must stay scoped to same-type nesting.
     *
     * <p>The bullet read "{@code AggregationRelationship} of sub-concerns into a parent
     * driver/goal", which is wrong twice over. The slash invites the cross-type reading, and
     * {@code Driver → Goal} and {@code Goal → Driver} are both {@code no} — Influence and
     * Association, with no {@code g} in either direction, so neither can be created. Only the
     * same-type orderings work: {@code Driver → Driver} and {@code Goal → Goal} are {@code cgnos}.
     * Separately, "concern" names none of the 62 concepts the matrix knows, so an agent taking the
     * clause literally is rejected at {@code create-element} before it reaches the relationship.</p>
     */
    @Test
    public void shouldAggregateOnlyWithinAType_soTheMotivationSubsetNamesOnlyAPermittedPair() {
        String subset = collapse(deltaLine(
                section(read(MOTIVATION), MOTIVATION_VIEW), NESTING_BULLET));

        assertTrue("The motivation page's nesting bullet no longer mentions an "
                + "AggregationRelationship, so the assertions below are guarding prose that has "
                + "moved. Re-point them — do not delete them.",
                subset.contains("AggregationRelationship"));

        List<Nesting> nested = nestingsIn(AGGREGATION_CLAUSE, subset);

        assertEquals("The motivation nesting bullet must aggregate exactly Driver and Goal — no "
                + "fewer, no more. Both are cgnos and both carry the guidance the bullet exists to "
                + "give. An extra self-consistent nesting for another motivation type would pass a "
                + "presence check and be legal in the matrix while still being outside what this "
                + "bullet prescribes. Parsed nestings: " + nested,
                Set.of("Driver", "Goal"), nestingSources(nested));

        assertTrue("The motivation page names an Aggregation into something other than a sub-part "
                + "of the SAME type. Driver→Goal and Goal→Driver are both no — Influence and "
                + "Association only, with NO g in either direction — so a bullet that aggregates "
                + "one into the other prescribes a call create-relationship rejects. The link "
                + "between a Driver and a Goal is the InfluenceRelationship the Draw list already "
                + "names. Offending clauses: " + crossTypeNestings(nested) + ". Subset reads: "
                + subset,
                crossTypeNestings(nested).isEmpty());

        assertFalse("The motivation page again describes what is nested as a \"concern\". No "
                + "ArchiMate concept has that name — the matrix knows 62 and Concern is not among "
                + "them — so the word tells an agent to create an element type that does not "
                + "exist. Subset reads: " + subset,
                subset.toLowerCase(java.util.Locale.ROOT).contains("concern"));
    }

    /**
     * The motivation build step must not restate what the subset just corrected.
     *
     * <p>Step 3 told the agent to "nest a sub-concern under its parent driver/goal" — the subset's
     * claim in the imperative mood, inside an instruction an agent executes, and carrying both of
     * the subset's defects. Correcting the subset alone would ship a fixed relationship list beside
     * an unfixed build step, which is the self-contradiction this class exists to prevent. The same
     * shape as {@link #shouldNotCallTheNestedEventsAggregated_soStep3AgreesWithItsOwnSubset}.</p>
     *
     * <p>Worth recording how this site was found, because the method that missed it is still in
     * use: the clause writes {@code Aggregation} without the {@code Relationship} suffix, so a
     * census of full type names walked straight past it. It was reached only by sweeping for bare
     * type names as well.</p>
     */
    @Test
    public void shouldNestOnlyWithinATypeInStep3_soTheMotivationStepAgreesWithItsOwnSubset() {
        String delta = collapse(deltaLine(
                section(read(MOTIVATION), MOTIVATION_VIEW), "Step 3:"));

        assertTrue("The motivation page's Step 3 no longer prescribes nesting via "
                + "parentViewObjectId, so the assertions below are guarding prose that has moved. "
                + "Re-point them — do not delete them.",
                delta.contains("parentViewObjectId"));

        assertFalse("Step 3 again describes what is nested as a \"concern\", which names no "
                + "ArchiMate concept. A build step is where an agent learns what a nesting means, "
                + "so this is the same defect as the subset's, in the mood that gets executed. "
                + "Delta reads: " + delta,
                delta.toLowerCase(java.util.Locale.ROOT).contains("concern"));

        assertFalse("Step 3 again offers a Driver and a Goal as interchangeable parents for the "
                + "same nested element. Aggregation between them is impermissible in both "
                + "directions (no / no — Influence and Association, no g), so only same-type "
                + "nesting is buildable. Delta reads: " + delta,
                delta.contains("driver/goal"));

        assertTrue("Step 3 must say what the nesting IS, having stopped saying what it is not. "
                + "Left bare, the reader is invited to supply the cross-type reading the subset "
                + "just removed. Delta reads: " + delta,
                delta.contains("same type"));
    }

    // ---- The index's Step 0 routing rows ------------------------------------------------------

    private static final String STEP_ZERO = "Step 0";

    /**
     * The organisation-structure row must name the pairs it prescribes.
     *
     * <p>It said "exclude Composition/owner-Assignment by nesting" and named no element types. Of
     * the four orderings {@code BusinessActor} and {@code BusinessRole} admit, Composition is
     * permitted only between a type and itself — {@code BusinessActor→BusinessRole} is
     * {@code fiotv} and the reverse is {@code fotv}, neither carrying {@code c} — and Assignment
     * only from actor to role, since {@code BusinessActor→BusinessActor} and
     * {@code BusinessRole→BusinessRole} are both {@code cfgostv} with no {@code i}, and
     * {@code BusinessRole→BusinessActor} is {@code fotv}. Five of the eight readings the row
     * admitted were impermissible.</p>
     *
     * <p>This row and its information-structure sibling were not in the original scope. They were
     * found by sweeping for relationship type names written <em>without</em> the
     * {@code Relationship} suffix — the census that located the other clauses counts only the full
     * form, and "exclude Composition/owner-Assignment" is invisible to it. That blind spot is
     * recorded in the shipped audit, because a future check reusing the same census inherits it.</p>
     */
    @Test
    public void shouldNestOrganizationStructureWithinAType_soTheIndexRowNamesOnlyPermittedPairs() {
        String row = collapse(rowContaining(section(read(INDEX), STEP_ZERO),
                "Organization structure", "the organisation-structure routing row"));

        assertTrue("The index's organisation-structure row no longer tells the reader to exclude a "
                + "relationship by nesting, so the assertions below are guarding prose that has "
                + "moved. Re-point them — do not delete them.",
                row.contains("Exclude by nesting"));

        List<Nesting> nested = nestingsIn(COMPOSITION_CLAUSE, row);

        assertEquals("The index row must nest exactly BusinessActor and BusinessRole — no fewer, "
                + "no more. Both Compositions are cfgostv and both carry the containment the row "
                + "exists to describe. An extra self-consistent nesting for another type would pass "
                + "a presence check and be legal in the matrix while still being outside this "
                + "row's scope. Parsed nestings: " + nested,
                Set.of("BusinessActor", "BusinessRole"), nestingSources(nested));

        assertTrue("The index row names a Composition into something other than a sub-part of the "
                + "SAME type. An actor is not composed of a role and a role is not composed of an "
                + "actor: BusinessActor→BusinessRole is fiotv and BusinessRole→BusinessActor is "
                + "fotv, neither carrying c. The link between them is the Assignment this row also "
                + "names. Offending clauses: " + crossTypeNestings(nested) + ". Row reads: " + row,
                crossTypeNestings(nested).isEmpty());

        List<AssignmentPair> named = pairsIn(row);

        assertEquals("The index row's Assignment clause must name exactly one pair. Naming none is "
                + "the defect this pin exists for — the row then covers four readings of which "
                + "three are rejected. Found: " + named,
                1, named.size());

        assertEquals("The index row names an Assignment from something other than a BusinessActor. "
                + "A role may not be assigned to an actor (fotv) and neither type may be assigned "
                + "to itself (cfgostv, no i). Found: " + named,
                "BusinessActor", named.get(0).source());

        assertEquals("The index row names an Assignment to something other than a BusinessRole. "
                + "BusinessActor→BusinessRole is fiotv and is the only permitted Assignment between "
                + "these two types. Found: " + named,
                "BusinessRole", named.get(0).target());
    }

    /**
     * The information-structure row must name the direction of the Realization it prescribes.
     *
     * <p>It said "draw Realization" over a two-type band and named neither endpoint. Only
     * {@code DataObject→BusinessObject} is permitted ({@code or}); the reverse is {@code o} and both
     * same-type orderings are {@code cgos}, none of the three carrying {@code r}. Three of the four
     * readings were impermissible, and the band order — objects above, data below — was the only
     * thing pointing at the legal one.</p>
     */
    @Test
    public void shouldDrawInformationRealizationOneWay_soTheIndexRowNamesOnlyAPermittedPair() {
        String row = collapse(rowContaining(section(read(INDEX), STEP_ZERO),
                "Information structure", "the information-structure routing row"));

        assertTrue("The index's information-structure row no longer prescribes a "
                + "RealizationRelationship, so the assertions below are guarding prose that has "
                + "moved. Re-point them — do not delete them.",
                row.contains("RealizationRelationship"));

        Set<String> arrows = new TreeSet<>();
        Matcher pair = NAMED_PAIR.matcher(row);
        while (pair.find()) {
            arrows.add(pair.group(1) + "→" + pair.group(2));
        }

        assertEquals("The index's information-structure row must name exactly the one permitted "
                + "Realization, DataObject→BusinessObject (or). Naming none leaves the direction to "
                + "the reader, and three of the four readings are rejected: BusinessObject→"
                + "DataObject is o, and both same-type orderings are cgos — no r in any of them. "
                + "Naming more than one means a pair arrived without being checked. Row reads: "
                + row,
                Set.of("DataObject→BusinessObject"), arrows);
    }

    // ---- The committed audit ------------------------------------------------------------------

    /**
     * A markdown filename, read from the audit's own declared coverage block.
     *
     * <p>Scoped to that block rather than run over the whole document, and the difference is not
     * cosmetic. A document-wide scan cannot tell "a page this audit covers" from "any filename the
     * prose happens to mention", so the first legitimate cross-reference — naming a reference page,
     * or another document under {@code docs/} — would fail the set equality with a message telling
     * the reader to re-run the sweep, which would be the wrong remedy for that failure. Declaring
     * coverage in one block makes the contract explicit and leaves the prose free.</p>
     */
    private static final Pattern MARKDOWN_FILE = Pattern.compile("\\b([a-z0-9-]+\\.md)\\b");

    /**
     * The committed relationship audit must still describe the corpus it claims to describe.
     *
     * <p>{@code docs/recipe-relationship-audit.md} records every pair the recipe library names,
     * every universal claim it makes, and every clause that prescribes a relationship without
     * naming its endpoints. A document like that is worth exactly as much as its freshness, and
     * nothing else in the build notices when a recipe page is added, renamed or removed underneath
     * it.</p>
     *
     * <p><strong>What this guard can and cannot do.</strong> It compares the set of pages under
     * {@code resources/recipes/} with the set the audit names, in both directions: a page the audit
     * does not mention fails, and a page the audit mentions that no longer exists fails. It
     * deliberately makes no claim about whether the tables are still <em>correct</em> — that needs
     * the matrix, which is not readable from a test (see {@link #NEVER_AN_ASSIGNMENT_TARGET}).
     * Coarse is the point: it fires when the corpus moves, and the response is to re-run the sweep,
     * never to edit the page list until the assertion passes.</p>
     */
    @Test
    public void shouldAuditEveryRecipePage_soTheCommittedAuditCannotSilentlyGoStale() {
        String audit = fencedBlock(read(firstResolvable(DOC_ROOTS).resolve(RELATIONSHIP_AUDIT)),
                "the audit's declared coverage");

        Set<String> onDisk = new TreeSet<>();
        for (Path page : recipePages()) {
            onDisk.add(page.getFileName().toString());
        }

        Set<String> named = new TreeSet<>();
        Matcher mentioned = MARKDOWN_FILE.matcher(audit);
        while (mentioned.find()) {
            named.add(mentioned.group(1));
        }

        assertEquals("The committed relationship audit no longer names exactly the recipe pages "
                + "that exist. A page present on disk but absent from the audit has shipped "
                + "unaudited; a page named by the audit but absent from disk means the audit "
                + "describes a corpus that has moved. Either way the fix is to re-run the sweep "
                + "the audit documents and update its tables — NOT to edit the page list until "
                + "this assertion passes. Note this reads the audit's declared-coverage block only, "
                + "so a filename mentioned in its prose is not what brought you here. On disk: "
                + onDisk + ". Declared by the audit: " + named,
                onDisk, named);
    }

    /** One {@code X into Y} nesting a clause names. */
    private record Nesting(String source, String target) {
        @Override
        public String toString() {
            return source + " into " + target;
        }
    }

    /**
     * Every {@code X into Y} nesting a clause of the given kind names.
     *
     * <p><strong>A list, deliberately, and not a map keyed by source type.</strong> An earlier
     * version returned {@code Map<String, String>}, which silently dropped a nesting whenever one
     * clause named the same source twice — and that is not a hypothetical shape. Measured: the
     * clause {@code "of an ApplicationComponent into ApplicationServices or an ApplicationComponent
     * into sub-components"} collapsed to the single entry {@code ApplicationComponent ->
     * sub-components}, so the illegal cross-type reading vanished before the guard ever saw it and
     * {@link #crossTypeNestings} reported clean. A guard that de-duplicates its own input cannot
     * report on what it de-duplicated away.</p>
     */
    private static List<Nesting> nestingsIn(Pattern clausePattern, String text) {
        StringBuilder clauses = new StringBuilder();
        Matcher clause = clausePattern.matcher(text.replace("`", ""));
        while (clause.find()) {
            clauses.append(clause.group(1)).append(' ');
        }

        List<Nesting> nested = new ArrayList<>();
        Matcher nesting = NESTED_INTO.matcher(clauses);
        while (nesting.find()) {
            nested.add(new Nesting(singular(nesting.group(1)), nesting.group(2)));
        }
        return nested;
    }

    /**
     * A type name with an English plural {@code s} removed, and only then.
     *
     * <p>The corpus writes a source type both ways — {@code a WorkPackage into sub-packages} and
     * {@code WorkPackages into their sub-packages} — so the plural has to be normalised. A blind
     * trailing-{@code s} strip is the obvious way and it is wrong on this corpus twice over, both
     * failures landing on the same type. {@code BusinessProcess} is one of the four types the
     * journey view's own element subset names: stripping blindly turns it into
     * {@code BusinessProces}, and turns the sub-part {@code processes} into {@code processe}. A
     * legitimate future clause nesting a {@code BusinessProcess} into sub-processes would then fail
     * on both sides at once — a correct edit reported as a regression.</p>
     *
     * <p>Two rules, and both are needed: no English plural ends in {@code ss}, which protects the
     * type name; and a plural in {@code es} after a sibilant stem drops both letters, which
     * recovers {@code process} from {@code processes} without touching {@code services} or
     * {@code packages}, whose stems end in {@code c} and {@code g}. Fixing only the first — which
     * an earlier version of this method did — moves the defect one field along rather than closing
     * it, and the mutation that adds a {@code BusinessProcess} nesting is what surfaces it.</p>
     */
    private static String singular(String typeName) {
        if (typeName.endsWith("ss")) {
            return typeName;
        }
        if (typeName.endsWith("ies") && typeName.length() > 3) {
            return typeName.substring(0, typeName.length() - 3) + "y";
        }
        if (typeName.endsWith("es") && typeName.length() > 2
                && "sxz".indexOf(typeName.charAt(typeName.length() - 3)) >= 0) {
            return typeName.substring(0, typeName.length() - 2);
        }
        return typeName.replaceAll("s$", "");
    }

    /**
     * The source types a clause nests, as a set.
     *
     * <p>Pinned as an <em>equality</em> by every caller rather than probed for presence, and that
     * distinction is the point. A presence check plus a cross-type check says "these are here, and
     * nothing here is cross-type" — which is silent about a nesting for some <em>other</em> type
     * that happens to be self-consistent. Measured: adding {@code or a Plateau into sub-plateaus}
     * to the roadmap bullet left all three of the earlier assertions green, and
     * {@code Plateau→Plateau} is {@code cfgost}, so no matrix check would have objected either. It
     * is out of the recipe's <em>scope</em>, and scope is what presence cannot express. Equality
     * can.</p>
     */
    private static Set<String> nestingSources(List<Nesting> nested) {
        Set<String> sources = new TreeSet<>();
        for (Nesting nesting : nested) {
            sources.add(nesting.source());
        }
        return sources;
    }

    /**
     * The nestings that are not into a sub-part of their own type.
     *
     * <p><strong>Not an independent cross-check of its callers' other assertion.</strong> This and
     * {@link #nestingSources} run off one parse, so a defect in {@link #nestingsIn} or
     * {@link #singular} moves both together rather than one catching what the other missed. They
     * are complementary in what they assert — which types are named, and that each is nested into
     * its own sub-part — not redundant, and not two independent signals. Reading them as a
     * cross-check would overstate the coverage.</p>
     *
     * <p><strong>The {@code sub-} prefix alone is not the test, and treating it as one was a hole.</strong>
     * {@code BusinessService into sub-components} carries the prefix and is still a cross-type
     * claim — the sub-part named belongs to {@code ApplicationComponent}. A guard whose failure
     * message says "nests a BusinessService into sub-services" must actually check that pairing, or
     * it asserts something weaker than it claims and a swapped-noun edit walks through it. The
     * noun after {@code sub-} is required to be a suffix of the source type name, which is exactly
     * how this corpus forms these words: {@code WorkPackage}/{@code sub-packages},
     * {@code ApplicationComponent}/{@code sub-components}, {@code Driver}/{@code sub-drivers}.</p>
     */
    private static Set<String> crossTypeNestings(List<Nesting> nested) {
        Set<String> crossType = new TreeSet<>();
        for (Nesting nesting : nested) {
            if (!ownSubPart(nesting.source(), nesting.target())) {
                crossType.add(nesting.toString());
            }
        }
        return crossType;
    }

    /**
     * Is {@code target} a {@code sub-} part whose noun belongs to {@code source}?
     *
     * <p>Hyphens are removed from both sides before comparing, because the target group tolerates a
     * hyphenated compound ({@code sub-application-components}) that a CamelCase type name does not
     * carry. The stem must be at least three characters: a shorter one matches too much —
     * {@code sub-es} stems to {@code e}, a suffix of {@code Deliverable},
     * {@code BusinessService} and every other type ending in that letter.</p>
     *
     * <p><strong>Stated limit: this is a lexical suffix test, not a type check.</strong> Across the
     * 62 ArchiMate concept names, 55 pairs share a family noun, so {@code TechnologyService} also
     * "owns" {@code sub-services}. That is harmless where it is used, because every caller pins the
     * exact set of source type names alongside this check and a wrong family member fails that set.
     * A caller using {@link #crossTypeNestings} <em>alone</em> would not be protected, which is why
     * none does.</p>
     */
    private static boolean ownSubPart(String source, String target) {
        if (!target.toLowerCase(Locale.ROOT).startsWith("sub-")) {
            return false;
        }
        String noun = singular(target.substring("sub-".length()))
                .toLowerCase(Locale.ROOT).replace("-", "");
        return noun.length() >= 3 && source.toLowerCase(Locale.ROOT).replace("-", "").endsWith(noun);
    }

    /**
     * Writes down what the scanner can and cannot parse, on input the corpus no longer contains.
     *
     * <p>Two of these forms have no live example: the {@code deployed-} qualifier was deleted by the
     * very fix this class pins, and no page currently drops the em-dash clause boundary. A parser
     * whose only evidence is the corpus it happens to face today is a parser whose behaviour is
     * unrecorded the moment that corpus changes — the same reasoning that put
     * {@link #shouldNotSwallowAPrescriptionThatMerelyFollowsOne} beside the prohibition
     * truncation.</p>
     */
    @Test
    public void shouldParseOnlyWhatItClaimsTo_soTheScannersReachIsWrittenDown() {
        assertEquals("a clause must yield every pair it names, not just the first",
                2, pairsIn("`AssignmentRelationship` Node→Artifact / Device→SystemSoftware "
                        + "— prose follows").size());

        assertEquals("the em-dash bounds the clause; arrows after it belong to the next "
                + "relationship and must not be read as Assignment claims",
                0, pairsIn("`AssignmentRelationship` none here — `RealizationRelationship` "
                        + "`Artifact`→`ApplicationComponent`").size());

        assertEquals("a lower-case deployed- qualifier is skipped, so the TARGET is captured",
                "ApplicationComponent",
                pairsIn("`AssignmentRelationship` Node→deployed-`ApplicationComponent`")
                        .get(0).target());

        assertEquals("a capitalised Deployed- qualifier must also be skipped — captured as the "
                + "target it would read Node→Deployed and lose the violation entirely",
                "ApplicationComponent",
                pairsIn("`AssignmentRelationship` Node→Deployed-`ApplicationComponent`")
                        .get(0).target());

        assertEquals("KNOWN LIMIT, asserted so it is a decision and not a surprise: a pair split "
                + "across a physical line break is invisible, because the scan is line-oriented",
                0, pairsIn("`AssignmentRelationship` Node→").size());
    }

    /** The scanner's line-level behaviour, exercised directly on synthetic copy. */
    private static List<AssignmentPair> pairsIn(String line) {
        List<AssignmentPair> found = new ArrayList<>();
        Matcher clause = ASSIGNMENT_CLAUSE.matcher(line);
        while (clause.find()) {
            Matcher pair = NAMED_PAIR.matcher(clause.group(1));
            while (pair.find()) {
                found.add(new AssignmentPair("<synthetic>", 1, pair.group(1), pair.group(2)));
            }
        }
        return found;
    }

    // ---- Schema conformance -----------------------------------------------------------------

    /**
     * Every parameter value the recipes prescribe must be one the tool actually accepts.
     *
     * <p>Ties the prose to the schema it quotes, so renaming an enum member breaks the build rather
     * than the field. It does not judge whether the prescribed value is the <em>right</em> one —
     * see the known limits on the class.</p>
     */
    @Test
    public void shouldPrescribeOnlyValuesTheToolAccepts_soCopyCannotDriftFromTheSchema() {
        String handler = readSource("ViewPlacementHandler.java");
        Set<String> arrangements = enumContaining(handler, "topology");
        Set<String> directions = enumContaining(handler, "vertical");
        Set<String> unknown = new TreeSet<>();

        for (Prescription p : prescriptions()) {
            Set<String> legal = "arrangement".equals(p.parameter()) ? arrangements : directions;
            if (!legal.contains(p.value())) {
                unknown.add(p + " — arrange-groups accepts " + legal);
            }
        }

        assertTrue("These recipe pages prescribe a parameter value the arrange-groups schema does "
                + "not accept, so an agent following them verbatim gets a rejected call: " + unknown,
                unknown.isEmpty());
    }

    /** The count pin. Without it a scanner matching nothing would pass over an empty set. */
    @Test
    public void shouldFindEveryPrescribedValue_soAnEmptyScanCannotPassAsClean() {
        List<Prescription> found = prescriptions();

        assertEquals("Prescribed-value count changed. If you added or removed an arrangement/"
                + "direction in a recipe, update EXPECTED_PRESCRIBED_VALUES in the same commit. If "
                + "you did not, the scanner has regressed and is no longer reading the copy it "
                + "guards. Found: " + found,
                EXPECTED_PRESCRIBED_VALUES, found.size());
    }

    /**
     * Guards the prohibition truncation. It exists to drop {@code "tree"} from a "do not use"
     * clause; if it ever swallowed the prescription that shares the line, the schema scan would
     * quietly stop covering that page.
     */
    @Test
    public void shouldNotSwallowAPrescriptionThatMerelyFollowsOne() {
        assertEquals("arrangement: \"column\"",
                prescriptivePart("- Step 7: `arrangement: \"column\"`. Do **not** use "
                        + "`arrangement: \"tree\"` — it reorders by connectivity.").trim()
                        .replaceAll("^- Step 7: `", "").replaceAll("`.*$", ""));

        assertTrue("a line with no prohibition must survive whole",
                prescriptivePart("- Step 7: `arrangement: \"topology\"`, `direction: \"vertical\"`.")
                        .contains("direction: \"vertical\""));
    }

    /**
     * Guards the header exclusion in {@link #rowContaining}. A header shares vocabulary with the
     * rows beneath it by design ("Action"), so without the exclusion a fragment occurring in both
     * returns two matches and trips the uniqueness check on legitimate copy — and a fragment
     * occurring only in the header would return the header itself as though it were a routing
     * entry.
     */
    @Test
    public void shouldIgnoreTableHeaderRows_soAHeaderIsNeverReadAsARoutingEntry() {
        String table = "| Shape | Action |\n"
                + "|---|---|\n"
                + "| **Application interaction** | No recipe. Action: lay it out flat. |\n";

        assertTrue("the body row must be returned, not the header that shares the word",
                rowContaining(table, "Action", "synthetic body row").contains("interaction"));
    }

    // ---- Scanner ----------------------------------------------------------------------------

    /** One prescribed parameter value, with the page and line that prescribes it. */
    private record Prescription(String file, int line, String parameter, String value) {
        @Override
        public String toString() {
            return file + ":" + line + " " + parameter + ": \"" + value + "\"";
        }
    }

    /** Every prescribed arrangement/direction across the recipe library, prohibitions excluded. */
    private static List<Prescription> prescriptions() {
        List<Prescription> found = new ArrayList<>();

        for (Path file : recipePages()) {
            String name = file.getFileName().toString();
            String[] lines = read(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String prescriptive = prescriptivePart(lines[i]);
                collect(found, name, i + 1, "arrangement", ARRANGEMENT.matcher(prescriptive));
                collect(found, name, i + 1, "direction", DIRECTION.matcher(prescriptive));
            }
        }
        return found;
    }

    /** One pair named inside an {@code AssignmentRelationship} clause, with where it is named. */
    private record AssignmentPair(String file, int line, String source, String target) {
        @Override
        public String toString() {
            return file + ":" + line + " " + source + "→" + target;
        }
    }

    /**
     * Every pair the shipped guidance names inside an {@code AssignmentRelationship} clause.
     *
     * <p>Scoped to <em>all</em> of {@code resources/}, not just {@code recipes/}. The recipe pages
     * are instances of the general rules in {@code reference/}, so a false relationship claim in a
     * general rule is the more damaging of the two — it is where a recipe's wrong instance comes
     * from. Reading only the instances would leave the source unchecked.</p>
     */
    private static List<AssignmentPair> assignmentPairs() {
        List<AssignmentPair> found = new ArrayList<>();

        for (Path file : guidancePages()) {
            String name = file.getFileName().toString();
            String[] lines = read(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher clause = ASSIGNMENT_CLAUSE.matcher(lines[i]);
                while (clause.find()) {
                    Matcher pair = NAMED_PAIR.matcher(clause.group(1));
                    while (pair.find()) {
                        found.add(new AssignmentPair(name, i + 1, pair.group(1), pair.group(2)));
                    }
                }
            }
        }
        return found;
    }

    private static void collect(List<Prescription> into, String file, int line, String parameter,
            Matcher matcher) {
        while (matcher.find()) {
            into.add(new Prescription(file, line, parameter, matcher.group(1)));
        }
    }

    /** The part of a line that prescribes, i.e. everything before it starts forbidding. */
    static String prescriptivePart(String line) {
        Matcher m = PROHIBITION.matcher(line);
        return m.find() ? line.substring(0, m.start()) : line;
    }

    /**
     * The values of the first {@code List.of(...)} in the source that contains {@code marker}.
     *
     * <p>Selected by content rather than by the variable it is assigned to: the handler declares
     * more than one {@code arrangementProp} and more than one {@code directionProp}, and only one
     * of each belongs to {@code arrange-groups}. {@code "topology"} and {@code "vertical"} each
     * appear in exactly one enum, so the content is the unambiguous key.</p>
     */
    private static Set<String> enumContaining(String source, String marker) {
        Matcher m = ENUM_LITERAL.matcher(source);
        while (m.find()) {
            Set<String> values = new LinkedHashSet<>();
            Matcher v = Pattern.compile("\"([A-Za-z_-]+)\"").matcher(m.group(1));
            while (v.find()) {
                values.add(v.group(1));
            }
            if (values.contains(marker)) {
                return values;
            }
        }
        throw new AssertionError("No enum in the handler contains \"" + marker + "\", so this test "
                + "would compare recipe copy against an empty set and pass over anything. The tool "
                + "schema has moved — re-point the scan rather than deleting the assertion.");
    }

    // ---- Prose helpers ----------------------------------------------------------------------

    /**
     * The body of a {@code ##} section, from its heading to the next one.
     *
     * <p>Scoped rather than whole-file on purpose: a recipe page carries more than one view class,
     * and the right string in the wrong section is exactly the failure these pins exist to catch.</p>
     */
    private static String section(String body, String headingFragment) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inSection = false;

        for (String line : lines) {
            boolean isHeading = line.startsWith("## ");
            if (isHeading) {
                if (inSection) {
                    break;
                }
                inSection = line.contains(headingFragment);
            }
            if (inSection) {
                out.append(line).append('\n');
            }
        }

        assertTrue("No \"## ...\" section matching \"" + headingFragment + "\" — the page has been "
                + "restructured and this pin is no longer reading what it names.",
                out.length() > 0);
        return out.toString();
    }

    /**
     * The indented bullets nested under one top-level bullet of a section.
     *
     * <p>Scoping matters more than it looks. The enclosing {@code section} runs from the page's
     * {@code ##} heading to the next one, so it carries the relationship subset AND every build
     * delta below it. A pin that read the whole section while claiming to guard the subset would
     * fire on a defect three bullets away — measured: restoring the word "aggregated" to the Step 3
     * delta reddened the subset assertion too, which makes a mutation table look strong while
     * hiding which surface is actually pinned.</p>
     *
     * <p>The boundary is the next unindented {@code - } bullet, and everything before it belongs to
     * the parent. Deliberately <em>not</em> "lines indented by two spaces": a hard-wrapped bullet
     * whose continuation line carries no indentation is still that bullet's text, and a collector
     * that silently dropped it would hand back a truncated string. That failure is invisible and
     * dangerous in the same breath — the "prose has moved" anchor would still find the event on the
     * first line while a part-of claim reintroduced past the wrap fell outside the returned text,
     * so the guard would pass over the very regression it exists to catch.</p>
     */
    private static String nestedBullets(String section, String parentFragment) {
        StringBuilder out = new StringBuilder();
        boolean inParent = false;

        for (String line : section.split("\n", -1)) {
            if (line.startsWith("- ")) {
                if (inParent) {
                    break;
                }
                inParent = line.contains(parentFragment);
                continue;
            }
            if (inParent) {
                out.append(line).append('\n');
            }
        }

        assertTrue("No bullets nested under \"" + parentFragment + "\" — the page has been "
                + "restructured and this pin is no longer reading what it names.", out.length() > 0);
        return out.toString();
    }

    /** The one delta line in a section carrying the given step label. */
    private static String deltaLine(String section, String stepLabel) {
        List<String> matches = new ArrayList<>();
        for (String line : section.split("\n", -1)) {
            if (line.contains(stepLabel)) {
                matches.add(line.trim());
            }
        }

        assertEquals("Expected exactly one \"" + stepLabel + "\" delta line in this section; found "
                + matches, 1, matches.size());
        return matches.get(0);
    }

    /**
     * The one table row in a section containing the given fragment.
     *
     * <p>Row-scoped rather than section-scoped: the fragment appearing <em>somewhere</em> on the
     * page says nothing about whether the routing table carries it.</p>
     *
     * <p>Body rows only. A header row is not a routing entry, and matching one would satisfy the
     * uniqueness check while handing back a row that answers a different question. The separator
     * line is the boundary — everything before it in a given table is header.</p>
     */
    private static String rowContaining(String section, String fragment, String what) {
        List<String> matches = new ArrayList<>();
        boolean inBody = false;

        for (String line : section.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                inBody = false; // this table ended; the next one starts with its own header
                continue;
            }
            if (trimmed.replace("|", "").trim().matches("^[-: ]*$")) {
                inBody = true;
                continue;
            }
            if (inBody && trimmed.contains(fragment)) {
                matches.add(trimmed);
            }
        }

        assertEquals("Expected exactly one table row to be " + what + " (matched on \"" + fragment
                + "\"); found " + matches.size() + ": " + matches, 1, matches.size());
        return matches.get(0);
    }

    /** Collapses wrapping so a prose assertion cannot fail on where the author broke the line. */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Strips the leading {@code *} margin so a wrapped Javadoc sentence reads as one string. */
    private static String stripJavadocMargin(String source) {
        return source.replaceAll("(?m)^\\s*\\*\\s?", " ");
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    private static List<Path> recipePages() {
        Path dir = recipesDir();
        List<Path> pages = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(pages::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue("No recipe pages found under " + dir.toAbsolutePath() + " — the scan cannot "
                + "silently cover nothing.", pages.size() >= 6);
        return pages;
    }

    private static Path recipesDir() {
        return firstResolvable(RESOURCE_ROOTS).resolve("recipes");
    }

    /** Every shipped guidance page, across {@code recipes/}, {@code reference/} and the rest. */
    private static List<Path> guidancePages() {
        Path root = firstResolvable(RESOURCE_ROOTS);
        List<Path> pages = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(pages::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue("Fewer guidance pages than the recipe library alone under "
                + root.toAbsolutePath() + " — the walk is not reaching what it claims to cover.",
                pages.size() > recipePages().size());
        return pages;
    }

    /**
     * The body of a fenced block inside a section — the topology drawing and the Rule beneath it.
     *
     * <p>Section-scoping is too coarse for a claim the topology block makes: these pages carry one
     * {@code ##} heading, so a section is the whole page, and an assertion written to pin the Rule
     * would be satisfied by the same words appearing in a delta step or the relationship subset.
     * That is exactly the contradiction-between-parts these pins exist to detect.</p>
     */
    private static String fencedBlock(String section, String what) {
        String[] lines = section.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inFence = false;

        for (String line : lines) {
            if (line.startsWith("```")) {
                if (inFence) {
                    break;
                }
                inFence = true;
                continue;
            }
            if (inFence) {
                out.append(line).append('\n');
            }
        }

        assertTrue("No fenced block found for " + what + " — the page has been restructured and "
                + "this pin is no longer reading what it names.", out.length() > 0);
        return out.toString();
    }

    private static String read(String recipeFileName) {
        return read(recipesDir().resolve(recipeFileName));
    }

    private static String readSource(String simpleFileName) {
        Path root = firstResolvable(SOURCE_ROOTS);
        try (var stream = Files.walk(root)) {
            Path match = stream.filter(p -> p.getFileName().toString().equals(simpleFileName))
                    .findFirst().orElse(null);
            assertTrue(simpleFileName + " not found under " + root.toAbsolutePath()
                    + " — this pin cannot silently pass over a file it never read.", match != null);
            return read(match);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path firstResolvable(String[] roots) {
        for (String candidate : roots) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new AssertionError("None of " + String.join(", ", roots) + " resolved from "
                + Paths.get("").toAbsolutePath() + " — the scan cannot silently cover nothing");
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
