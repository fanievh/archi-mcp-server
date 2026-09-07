package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The contract that stops the next approval card being born silent.
 *
 * <p><strong>The invariant.</strong> When a session runs behind the approval gate, a mutating tool
 * stores a proposal instead of writing. The {@code proposedChanges} map on that proposal is the
 * only description of the pending write anyone gets: it travels verbatim onto the wire, where any
 * agent can read it back, and verbatim into the approval card's Technical-details / Copy-JSON
 * disclosure. A parameter the accessor accepts but never puts into that map is applied on approval
 * and named nowhere.</p>
 *
 * <p><strong>Why a contract rather than another round of fixes.</strong> This family had already
 * been closed once, card by card, with nothing holding it — and the next omission surfaced thirty
 * days later during an unrelated review. Repairing call sites does not stop the next site. So every
 * proposal site must be classified exactly once: either the parse below proves it discloses every
 * parameter its enclosing method accepts, or it carries a line in
 * {@code tools/approval-card-gaps.txt} with a status and a reason. A site in neither fails the
 * build; a site in both fails too.</p>
 *
 * <p><strong>Source-parsing only, on purpose.</strong> This test builds no model. Its sibling
 * effective-state contract builds an EMF fixture and is therefore quarantined to the display pass;
 * a parse has no such dependency and runs in the wider headless lane, where it guards every commit
 * rather than only the release gate.</p>
 *
 * <p><strong>The parse proves itself first.</strong> A parser that matches nothing reads exactly
 * like a clean scan — a wrong assertion, not a broken one. So {@link #EXPECTED_SITE_COUNT} is
 * asserted before anything is asserted about the sites' contents.</p>
 */
public class ApprovalCardContractTest {

    private static final String IMPL_FILE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

    private static final String GAP_FILE = "tools/approval-card-gaps.txt";

    private static final String COLLABORATOR_FILE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/DeleteApprovalCardText.java";

    /**
     * The measured number of proposal sites in the accessor.
     *
     * <p>This is a FLOOR as much as a count, and the reason matters: a parse that silently matched
     * nothing, or matched a plausible-looking subset, would report a clean scan while checking
     * nothing at all. Two independent greps agree on this number — one over the
     * {@code proposedChanges} map declarations, one over the {@code storeAsProposal} calls they
     * feed — so there is no site without a card and no card without a site.</p>
     *
     * <p>Adding a proposal site is expected to raise this constant, in the same commit that
     * classifies the new site. Lowering it means a site was removed; verify that before editing.</p>
     */
    private static final int EXPECTED_SITE_COUNT = 42;

    /**
     * The ratchet click. Each closed exemption deletes one line from the registry and lowers this
     * by one, so the registry is monotonically shrinking rather than a list that grows whenever it
     * is easier to admit a gap than to close one. LOWER-ONLY — never raise it.
     */
    private static final int GAP_ENTRY_CEILING = 15;

    /**
     * The routing key. It selects which session's gate applies and is never written to the model,
     * so it appears in none of the sites' cards and is not an omission in any of them.
     */
    private static final Set<String> ROUTING_PARAMETERS = Set.of("sessionId");

    /**
     * FALSE-POSITIVE CLASS: a parameter destructured before disclosure never appears under its own
     * name. {@code semanticAttributes} is unpacked into accessType / associationDirected /
     * influenceStrength, each disclosed under its own key, so a name-versus-keys diff would report
     * the container as missing while all of its content is on the card.
     */
    private static final Set<String> DESTRUCTURED_PARAMETERS = Set.of("semanticAttributes");

    /**
     * FALSE-POSITIVE CLASS: parameters that travel as {@code storeAsProposal}'s OWN arguments,
     * beside {@code proposedChanges} rather than inside it. These are dedicated card fields — the
     * human reads {@code description} as the card's visible row — so a parameter carried there is
     * disclosed more prominently than a map key, not less.
     *
     * <p><strong>Scoped per site, deliberately.</strong> A bare name-based exemption would excuse a
     * parameter called {@code description} at ALL 42 sites, including a future one where it means
     * "the documentation text to write" and genuinely belongs in the card. That would reproduce the
     * born-silent defect inside the guard built to prevent it. Only the two sites that actually pass
     * these values through to {@code storeAsProposal} are exempted; a third site acquiring such a
     * parameter fails the build until someone rules on it.</p>
     */
    private static final Map<String, Set<String>> CARD_FIELD_PARAMETERS = Map.of(
            "executeBulk", Set.of("description", "intent"),
            "applyViewLayout", Set.of("description"));

    /** The four geometry keys {@code ProposalBuilder.putBounds} contributes. */
    private static final List<String> BOUNDS_KEYS = List.of("x", "y", "width", "height");

    private static final Pattern SITE = Pattern.compile("proposedChanges\\s*=\\s*new LinkedHashMap");
    private static final Pattern METHOD_DECL =
            Pattern.compile("^    (public|private|protected)\\b.*\\(");
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(");
    private static final Pattern LITERAL_PUT =
            Pattern.compile("proposedChanges\\.put\\(\\s*\"([^\"]+)\"");
    private static final Pattern PUT_IF_PRESENT =
            Pattern.compile("ProposalBuilder\\.putIfPresent\\(");
    private static final Pattern TARGET_IDS = Pattern.compile("\\w*[tT]argetIds\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern TOOL_NAME =
            Pattern.compile("storeAsProposal\\(\\s*sessionId\\s*,\\s*\"([^\"]+)\"", Pattern.DOTALL);

    // ---- AC: the parse proves itself before it proves anything else -------------------------

    @Test
    public void shouldFindEveryProposalSite_beforeAssertingAnythingAboutThem() throws IOException {
        List<Site> sites = parseSites();

        assertEquals("The approval-card contract parsed " + sites.size() + " proposal sites but "
                + EXPECTED_SITE_COUNT + " are expected. A parse that finds ZERO, or a plausible "
                + "SMALLER subset, reads exactly like a clean scan while checking nothing — which "
                + "is a wrong assertion, not a broken one. If a proposal site was genuinely added "
                + "or removed, update EXPECTED_SITE_COUNT in the same commit that classifies it; "
                + "otherwise the parser has drifted from the source it reads.",
                EXPECTED_SITE_COUNT, sites.size());
    }

    @Test
    public void shouldResolveEverySiteToADistinctEnclosingMethod() throws IOException {
        // The registry is keyed by enclosing method, so two sites sharing a key would let one
        // site's exemption silently cover the other's silence.
        List<Site> sites = parseSites();
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (Site site : sites) {
            if (!seen.add(site.method)) {
                duplicated.add(site.method);
            }
        }
        assertTrue("These enclosing method names carry more than one proposal site, so the "
                + "registry key is not unique: " + duplicated, duplicated.isEmpty());
        assertEquals(EXPECTED_SITE_COUNT, seen.size());
    }

    @Test
    public void shouldResolveEverySiteToATool() throws IOException {
        List<String> unresolved = new ArrayList<>();
        for (Site site : parseSites()) {
            if (site.tool == null || site.tool.isBlank()) {
                unresolved.add(site.method);
            }
        }
        assertTrue("These sites' storeAsProposal calls did not yield a tool name, so the parse "
                + "cannot be trusted about what they disclose: " + unresolved, unresolved.isEmpty());
    }

    // ---- AC: complete, or registered — never neither, never both ----------------------------

    @Test
    public void shouldClassifyEverySite_asCompleteOrRegistered() throws IOException {
        List<Site> sites = parseSites();
        assertEquals("precondition: the whole population must be parsed before it is classified",
                EXPECTED_SITE_COUNT, sites.size());
        Map<String, GapEntry> registry = readGapRegistry();

        List<String> unclassified = new ArrayList<>();
        for (Site site : sites) {
            GapEntry entry = registry.get(site.method);
            Set<String> exempted = entry == null ? Set.of() : entry.exemptedParameters;
            List<String> undisclosed = new ArrayList<>(undisclosedParameters(site));
            undisclosed.removeAll(exempted);
            if (!undisclosed.isEmpty()) {
                unclassified.add(site.method + " (" + site.tool + ") does not disclose "
                        + undisclosed + (entry == null ? " and has no registry entry"
                                : " — its registry entry exempts only " + exempted));
            }
        }

        assertTrue("These approval cards omit a parameter the approval will write, and nothing in "
                + GAP_FILE + " admits THAT PARAMETER. A human approving one of these is approving a "
                + "change the card did not describe. Either put the parameter into that site's "
                + "proposedChanges map, or name it on that site's registry line with a reason. "
                + "An entry exempts exactly the parameters it lists — registering a site does not "
                + "blanket-exempt everything else on it:\n  "
                + String.join("\n  ", unclassified), unclassified.isEmpty());
    }

    @Test
    public void shouldNotRegisterASite_thatIsAlreadyComplete() throws IOException {
        // Classified twice. An exemption left behind after its site was fixed reads as a
        // still-open gap and, worse, would keep the ceiling artificially high — so the next real
        // omission could be admitted without a click.
        Map<String, GapEntry> registry = readGapRegistry();
        List<String> redundant = new ArrayList<>();
        List<String> staleParameters = new ArrayList<>();
        for (Site site : parseSites()) {
            GapEntry entry = registry.get(site.method);
            if (entry == null) {
                continue;
            }
            List<String> undisclosed = undisclosedParameters(site);
            if (undisclosed.isEmpty()) {
                redundant.add(site.method);
                continue;
            }
            for (String exempted : entry.exemptedParameters) {
                if (!undisclosed.contains(exempted)) {
                    staleParameters.add(site.method + " exempts '" + exempted + "', which it now "
                            + "discloses (or no longer accepts)");
                }
            }
        }
        assertTrue("These sites now disclose every parameter they accept, so their entry in "
                + GAP_FILE + " is stale. Delete each line and lower GAP_ENTRY_CEILING by the same "
                + "number in this commit: " + redundant, redundant.isEmpty());
        assertTrue("These registry entries name a parameter that is no longer a gap. An exemption "
                + "must not outlive what it describes, or it silently widens: "
                + staleParameters, staleParameters.isEmpty());
    }

    @Test
    public void shouldNotRegisterASite_thatDoesNotExist() throws IOException {
        Set<String> methods = new LinkedHashSet<>();
        for (Site site : parseSites()) {
            methods.add(site.method);
        }
        List<String> stale = new ArrayList<>(readGapRegistry().keySet());
        stale.removeAll(methods);

        assertTrue("These entries in " + GAP_FILE + " name a proposal site that no longer exists. "
                + "A registry line pointing at nothing exempts nothing while still holding the "
                + "ceiling up: " + stale, stale.isEmpty());
    }

    // ---- AC: the lower-only pawl ------------------------------------------------------------

    @Test
    public void shouldKeepTheGapRegistryAtOrBelowItsCeiling_whichOnlyEverLowers() throws IOException {
        int entries = readGapRegistry().size();
        assertTrue("The approval-card gap registry has " + entries + " entries against a ceiling of "
                + GAP_ENTRY_CEILING + ". The ceiling is LOWER-ONLY: close the gap by disclosing the "
                + "parameter, delete the line, and click GAP_ENTRY_CEILING down by one in the same "
                + "commit — never the reverse.", entries <= GAP_ENTRY_CEILING);
    }

    @Test
    public void shouldGiveEveryRegistryEntryAStatusAndAReason() throws IOException {
        Set<String> statuses = Set.of("FROZEN-COMPOUND", "COUNT-SUMMARISED");
        List<String> malformed = new ArrayList<>();
        for (Map.Entry<String, GapEntry> entry : readGapRegistry().entrySet()) {
            GapEntry gap = entry.getValue();
            if (!statuses.contains(gap.status) || gap.reason.isEmpty()
                    || gap.exemptedParameters.isEmpty()) {
                malformed.add(entry.getKey() + " -> status '" + gap.status + "', exempts "
                        + gap.exemptedParameters + ", reason '" + gap.reason + "'");
            }
        }
        assertTrue("Every entry must read '<enclosing-method> <STATUS> # <reason>' with STATUS one "
                + "of " + new TreeSet<>(statuses) + " and a non-empty reason — an exemption without "
                + "a reason is silence with extra steps: " + malformed, malformed.isEmpty());
    }

    // ---- The three false-positive classes are budgeted, not merely asserted -----------------

    @Test
    public void shouldExcludeTheSubjectIdCarriedByTargetIds() throws IOException {
        // FALSE-POSITIVE CLASS: the id naming WHAT is being changed travels as a targetIds
        // argument, which is how the staleness guard tracks it. delete-view is the clearest case:
        // its card's map holds viewId alone by design, and its cascade counts live in the
        // description sentence.
        Site deleteView = siteFor("deleteView");
        assertTrue("precondition: deleteView must be tracked through targetIds",
                deleteView.targetIdArguments.contains("viewId"));
        assertTrue(undisclosedParameters(deleteView).isEmpty());
    }

    @Test
    public void shouldExcludeADestructuredParameter() throws IOException {
        // create-relationship never puts semanticAttributes; it puts the three values unpacked
        // from it. A naive signature-versus-keys diff reports the container as missing.
        Site createRelationship = siteFor("createRelationship");
        assertTrue("precondition: the parameter really is in the signature",
                createRelationship.parameters.contains("semanticAttributes"));
        assertFalse("precondition: and really is absent from the map as a key",
                createRelationship.keys.contains("semanticAttributes"));
        assertTrue(createRelationship.keys.contains("accessType"));
        assertTrue(undisclosedParameters(createRelationship).isEmpty());
    }

    @Test
    public void shouldExcludeAParameterCarriedAsACardFieldArgument() throws IOException {
        // bulk-mutate passes intent straight through as storeAsProposal's intent argument and
        // folds description into the label that becomes its description argument. Both are
        // dedicated card fields, so neither is an omission — its one genuine omission was
        // continueOnError, and that is now disclosed.
        Site executeBulk = siteFor("executeBulk");
        assertTrue(executeBulk.parameters.contains("description"));
        assertTrue(executeBulk.parameters.contains("intent"));
        assertTrue("continueOnError decides whether a partial failure leaves partial writes behind, "
                + "which is exactly what a human should know before approving a large batch",
                executeBulk.keys.contains("continueOnError"));
        assertTrue(undisclosedParameters(executeBulk).isEmpty());
    }

    // ---- Parsing -----------------------------------------------------------------------------

    /** One proposal site: its enclosing method, the tool it proposes, and what its card carries. */
    private static final class Site {
        final String method;
        final String tool;
        final List<String> parameters;
        final Set<String> keys;
        /**
         * The identifiers passed to this site's {@code targetIds(...)} call, as whole tokens.
         * Held as a token set rather than the raw argument text because a substring test would
         * excuse a parameter named {@code id} merely because some unrelated {@code elementId}
         * appears in that call — a false negative in the direction this contract exists to prevent.
         */
        final Set<String> targetIdArguments;

        Site(String method, String tool, List<String> parameters, Set<String> keys,
                Set<String> targetIdArguments) {
            this.method = method;
            this.tool = tool;
            this.parameters = parameters;
            this.keys = keys;
            this.targetIdArguments = targetIdArguments;
        }
    }

    /**
     * The parameters this site accepts but its card does not disclose, after the three
     * false-positive classes are removed.
     */
    private static List<String> undisclosedParameters(Site site) {
        Set<String> cardFields = CARD_FIELD_PARAMETERS.getOrDefault(site.method, Set.of());
        List<String> undisclosed = new ArrayList<>();
        for (String parameter : site.parameters) {
            if (ROUTING_PARAMETERS.contains(parameter)
                    || DESTRUCTURED_PARAMETERS.contains(parameter)
                    || cardFields.contains(parameter)
                    || site.keys.contains(parameter)
                    || site.targetIdArguments.contains(parameter)) {
                continue;
            }
            undisclosed.add(parameter);
        }
        return undisclosed;
    }

    private Site siteFor(String method) throws IOException {
        for (Site site : parseSites()) {
            if (site.method.equals(method)) {
                return site;
            }
        }
        throw new AssertionError("No proposal site found in an enclosing method named '" + method
                + "'. This test names it deliberately to budget a false-positive class; if the "
                + "method was renamed, re-point the test rather than deleting the coverage.");
    }

    /**
     * Parses every proposal site: from each {@code proposedChanges} map declaration forward to the
     * {@code storeAsProposal} call it feeds, collecting the keys contributed by literal puts and by
     * each shared map-filling helper, and backward to the enclosing method's signature.
     */
    private List<Site> parseSites() throws IOException {
        List<String> lines = Files.readAllLines(locateRepoFile(IMPL_FILE), StandardCharsets.UTF_8);
        List<Site> sites = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!SITE.matcher(lines.get(i)).find()) {
                continue;
            }
            String signature = enclosingSignature(lines, i);
            Matcher name = METHOD_NAME.matcher(signature);
            assertTrue("could not read a method name out of: " + signature, name.find());

            StringBuilder body = new StringBuilder();
            int end = i;
            while (end < lines.size() && !lines.get(end).contains("storeAsProposal(")) {
                body.append(lines.get(end)).append('\n');
                end++;
            }
            assertTrue("a proposedChanges map at line " + (i + 1) + " reaches no storeAsProposal "
                    + "call; the parse would silently under-report its keys", end < lines.size());

            String tail = String.join("\n",
                    lines.subList(end, Math.min(end + 24, lines.size())));

            sites.add(new Site(name.group(1), toolName(tail),
                    parameterNames(signature), disclosedKeys(body.toString()),
                    targetIdArguments(tail)));
        }
        return sites;
    }

    /** Walks back from a site to the declaration of the method that contains it. */
    private static String enclosingSignature(List<String> lines, int siteLine) {
        int start = -1;
        for (int k = siteLine; k >= 0; k--) {
            String line = lines.get(k);
            if (METHOD_DECL.matcher(line).find() && !line.trim().endsWith(";")) {
                start = k;
                break;
            }
        }
        assertTrue("no enclosing method declaration found above line " + (siteLine + 1), start >= 0);
        StringBuilder signature = new StringBuilder();
        for (int k = start; k < lines.size(); k++) {
            signature.append(lines.get(k)).append(' ');
            if (lines.get(k).contains("{")) {
                break;
            }
        }
        return signature.toString();
    }

    private static List<String> parameterNames(String signature) {
        String args = callArguments(signature, signature.indexOf('('));
        List<String> names = new ArrayList<>();
        for (String argument : splitTopLevel(args)) {
            String[] words = argument.trim().split("\\s+");
            if (words.length > 0 && !words[words.length - 1].isEmpty()) {
                names.add(words[words.length - 1]);
            }
        }
        return names;
    }

    /**
     * The keys this site's card carries: literal puts, plus every key the shared map-filling
     * collaborators contribute. A helper this method does not know about would under-report the
     * card and raise a false omission, which is the safe direction to be wrong in.
     */
    private static Set<String> disclosedKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher literal = LITERAL_PUT.matcher(body);
        while (literal.find()) {
            keys.add(literal.group(1));
        }
        if (body.contains("ProposalBuilder.putBounds(")) {
            keys.addAll(BOUNDS_KEYS);
        }
        if (body.contains("ProposalBuilder.putVisuals(")
                || body.contains("ProposalBuilder.putContainerVisuals(")) {
            Collections.addAll(keys, "styling", "imageParams");
        }
        if (body.contains("ProposalBuilder.putStyling(")) {
            keys.add("styling");
        }
        if (body.contains("ProposalBuilder.putImageParams(")) {
            keys.add("imageParams");
        }
        if (body.contains("DeleteApprovalCardText.putViewCounts(")) {
            keys.addAll(collaboratorKeys("putViewCounts"));
        }
        if (body.contains("DeleteApprovalCardText.putFolderCounts(")) {
            keys.addAll(collaboratorKeys("putFolderCounts"));
        }
        Matcher fold = PUT_IF_PRESENT.matcher(body);
        while (fold.find()) {
            List<String> arguments = splitTopLevel(callArguments(body, fold.end() - 1));
            // argument 0 is the map; the rest alternate key, value.
            for (int a = 1; a < arguments.size(); a += 2) {
                String argument = arguments.get(a);
                assertTrue("putIfPresent's key positions must be string literals so the contract "
                        + "can read them; found: " + argument,
                        argument.startsWith("\"") && argument.endsWith("\""));
                keys.add(argument.substring(1, argument.length() - 1));
            }
        }
        return keys;
    }

    /**
     * The literal keys a {@code DeleteApprovalCardText} map-filler actually writes, read from that
     * file rather than mirrored here.
     *
     * <p>A hard-coded copy of another file's key set is a shadow that drifts silently, and it drifts
     * in the dangerous direction: it would credit a site with disclosing a key the collaborator no
     * longer writes, turning a real gap into a green build. Reading the source keeps the two in step
     * by construction, and the non-empty assertion means a renamed method fails loudly instead of
     * contributing nothing.</p>
     */
    private static Set<String> collaboratorKeys(String methodName) {
        String source;
        try {
            source = Files.readString(locateRepoFile(COLLABORATOR_FILE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + COLLABORATOR_FILE, e);
        }
        int start = source.indexOf(" " + methodName + "(");
        assertTrue("no method named '" + methodName + "' in " + COLLABORATOR_FILE + ". The contract "
                + "credits proposal sites with the keys this collaborator writes; if it was renamed, "
                + "re-point this lookup rather than dropping the credit.", start > 0);
        int end = source.indexOf("\n    }", start);
        assertTrue("unterminated method body for '" + methodName + "'", end > start);

        Set<String> keys = new LinkedHashSet<>();
        Matcher put = LITERAL_PUT.matcher(source.substring(start, end));
        while (put.find()) {
            keys.add(put.group(1));
        }
        assertFalse("'" + methodName + "' contributed no keys — a parse that finds nothing here "
                + "would silently under-credit the delete cards", keys.isEmpty());
        return keys;
    }

    private static String toolName(String tail) {
        Matcher tool = TOOL_NAME.matcher(tail);
        return tool.find() ? tool.group(1) : null;
    }

    /**
     * The whole-token identifiers passed to this site's {@code targetIds(...)} call.
     *
     * <p>Every {@code *targetIds(} call in the window is read, not just the first: the accessor has
     * three such helpers ({@code targetIds}, {@code compoundTargetIds}, {@code bulkTargetIds}) and
     * binding to whichever happens to appear first would silently read the wrong argument list.</p>
     */
    private static Set<String> targetIdArguments(String tail) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher target = TARGET_IDS.matcher(tail);
        while (target.find()) {
            Matcher token = IDENTIFIER.matcher(callArguments(tail, target.end() - 1));
            while (token.find()) {
                tokens.add(token.group());
            }
        }
        return tokens;
    }

    /**
     * The text between a call's parentheses, balanced.
     *
     * <p>String literals are skipped while balancing. Without that, a value argument containing an
     * unmatched parenthesis — the accessor already writes card values like {@code "(clear)"} and
     * {@code "(clear to manual)"} — would close the scan early and silently truncate the parsed
     * argument list, under-reporting disclosed keys instead of failing loudly. {@code splitTopLevel}
     * below has always tracked literals; this method must agree with it or the two disagree about
     * the same text.</p>
     */
    private static String callArguments(String text, int openParen) {
        assertTrue("expected a '(' at the call site", openParen >= 0);
        int depth = 0;
        boolean inString = false;
        for (int i = openParen; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openParen + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced parentheses reading a call at offset " + openParen);
    }

    /**
     * Splits an argument list on commas that are not nested inside parentheses, brackets, braces,
     * generic type arguments, or a string literal. A naive split on ',' tears
     * {@code Map<String, String> properties} in half and silently loses the parameter name.
     */
    private static List<String> splitTopLevel(String arguments) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < arguments.length()) {
                    current.append(arguments.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().trim().isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    /**
     * Reads the committed registry, keyed by enclosing method name.
     *
     * <p>A repeated key is rejected outright rather than collapsed: entries are keyed, so a
     * duplicate would overwrite its twin and the map would report fewer entries than the file
     * holds. An extra, un-clicked line could then hide behind a name already present — precisely
     * the "admit a gap without a reviewable ceiling click" move the pawl exists to prevent.</p>
     */
    private Map<String, GapEntry> readGapRegistry() throws IOException {
        Path file = locateRepoFile(GAP_FILE);
        Map<String, GapEntry> entries = new LinkedHashMap<>();
        int lineCount = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lineCount++;
            int hash = line.indexOf('#');
            assertTrue("registry entry has no '# <reason>': " + line, hash > 0);
            String reason = line.substring(hash + 1).trim();
            String[] fields = line.substring(0, hash).trim().split("\\s+");
            assertEquals("a registry entry must read '<enclosing-method> <STATUS> "
                    + "<parameter,parameter,...> # <reason>' — the parameter list is what the entry "
                    + "actually exempts, so an entry without one exempts nothing and is malformed: "
                    + line, 3, fields.length);

            String method = fields[0];
            assertFalse("'" + method + "' is listed twice in " + GAP_FILE + ". Entries are keyed by "
                    + "enclosing method, so a duplicate would collapse into one and let the file "
                    + "grow past the ceiling without a click. Merge the two lines into one.",
                    entries.containsKey(method));

            Set<String> exempted = new LinkedHashSet<>();
            for (String parameter : fields[2].split(",")) {
                assertFalse("empty parameter name in: " + line, parameter.isBlank());
                assertTrue("'" + parameter + "' is listed twice on one entry: " + line,
                        exempted.add(parameter.trim()));
            }
            entries.put(method, new GapEntry(fields[1], exempted, reason));
        }
        assertEquals("every non-comment line in " + GAP_FILE + " must survive parsing into exactly "
                + "one entry", lineCount, entries.size());
        return entries;
    }

    /** One registry line: the status, the parameters it exempts, and why. */
    private static final class GapEntry {
        final String status;
        final Set<String> exemptedParameters;
        final String reason;

        GapEntry(String status, Set<String> exemptedParameters, String reason) {
            this.status = status;
            this.exemptedParameters = exemptedParameters;
            this.reason = reason;
        }
    }

    /**
     * Resolves a repo-relative path by walking upward from the working directory.
     *
     * <p>Fails explicitly rather than skipping when the repo cannot be found: the alternative
     * failure mode for a ratchet is to quietly stop ratcheting.</p>
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
                + "inside the repository checkout so it can read the accessor source and the "
                + "committed registry; running it from elsewhere would silently disable the "
                + "approval-card contract.");
    }
}
