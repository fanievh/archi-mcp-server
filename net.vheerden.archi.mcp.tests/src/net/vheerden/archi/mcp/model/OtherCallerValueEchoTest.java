package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.EMOJI;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.whyMalformed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.FolderHandler;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.SessionHandler;
import net.vheerden.archi.mcp.handlers.SpecializationHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins how the filter, format, exclude-field, layout-option, router-type and concept-type refusals
 * repeat the value a caller sent: an ordinary value reads exactly as before, with the same suggestion,
 * a long one is cut to its first 40 UTF-16 units without splitting a character and says it was cut,
 * and no control character, unpaired surrogate, line separator, format character, quote or backslash
 * reaches the message raw. Messages are read after decoding the JSON response, so an escape asserted
 * here is text in the message.
 *
 * <p>Before, each of these refusals repeated the value whole and raw: a lone surrogate reached the HTTP
 * response as bytes that are not UTF-8, a control character reached the message, and a long value was
 * repeated in full.</p>
 */
public class OtherCallerValueEchoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String GROUP = "grp-1";
    private static final String MUST_BE = ". Must be 'row', 'column', or 'grid'.";
    private static final String SORTED_EXCLUDE_FIELDS = String.join(", ",
            FieldSelector.VALID_EXCLUDE_FIELDS.stream().sorted().toList());

    private ViewContentsFixture f;
    private final CommandRegistry registry = new CommandRegistry();

    /**
     * One refusal site: the tool and argument that reach it, and the text its refusal puts around the value.
     * Where the handler reads the argument as absent when it is blank, a blank value never reaches the site.
     */
    private record Site(String name, String prefix, String suffix, boolean blankIsAbsent, Reader reader) {}

    private interface Reader {
        String refuse(OtherCallerValueEchoTest test, String value);
    }

    private static final List<Site> SITES = List.of(
            own("search-elements type", "Invalid element type: ", "", "search-elements", "type", false),
            own("search-elements layer", "Invalid layer: ", "", "search-elements", "layer", false),
            own("search-elements exclude", "Invalid exclude field: ", "", "search-elements", "exclude", true),
            own("search-elements format", "Invalid format: ", "", "search-elements", "format", false),
            own("search-relationships type", "Invalid relationship type: ", "", "search-relationships", "type", false),
            own("search-relationships sourceLayer", "Invalid sourceLayer: ", "", "search-relationships",
                    "sourceLayer", false),
            own("search-relationships targetLayer", "Invalid targetLayer: ", "", "search-relationships",
                    "targetLayer", false),
            own("search-relationships exclude", "Invalid exclude field: ", "", "search-relationships", "exclude", true),
            own("search-relationships format", "Invalid format: ", "", "search-relationships", "format", false),
            own("get-relationships format", "Invalid format: ", "", "get-relationships", "format", false),
            own("get-relationships exclude", "Invalid exclude field: ", "", "get-relationships", "exclude", true),
            reaching("get-relationships excludeTypes", "Invalid relationship type: ", "", "get-relationships",
                    "excludeTypes"),
            reaching("get-relationships includeTypes", "Invalid relationship type: ", "", "get-relationships",
                    "includeTypes"),
            own("get-relationships filterLayer", "Invalid layer: ", "", "get-relationships", "filterLayer", false),
            served("get-views exclude", "Invalid exclude field: ", "", "get-views", "exclude", true),
            served("get-views format", "Invalid format: ", "", "get-views", "format", false),
            served("get-view-contents format", "Invalid format: ", "", "get-view-contents", "format", false),
            served("get-view-contents exclude", "Invalid exclude field: ", "", "get-view-contents", "exclude", true),
            own("get-element exclude", "Invalid exclude field: ", "", "get-element", "exclude", true),
            own("get-folders exclude", "Invalid exclude field: ", "", "get-folders", "exclude", true),
            own("get-folder-tree exclude", "Invalid exclude field: ", "", "get-folder-tree", "exclude", true),
            own("set-session-filter type", "Invalid element type: ", "", "set-session-filter", "type", false),
            own("set-session-filter layer", "Invalid layer: ", "", "set-session-filter", "layer", false),
            own("set-session-filter fields", "Invalid fields preset: ", ". Valid presets: minimal, standard, full",
                    "set-session-filter", "fields", false),
            own("set-session-filter exclude", "Invalid exclude field: ", ". Valid fields: " + SORTED_EXCLUDE_FIELDS,
                    "set-session-filter", "exclude", true),
            served("auto-layout-and-route mode", "Invalid mode: ", "", "auto-layout-and-route", "mode", false),
            served("auto-layout-and-route targetRating", "Invalid targetRating: ", "", "auto-layout-and-route",
                    "targetRating", false),
            own("create-specialization conceptType", "Unknown ArchiMate concept type: ", "", "create-specialization",
                    "conceptType", false),
            served("auto-route-connections strategy", "Invalid strategy: ", ". Valid: orthogonal, clear",
                    "auto-route-connections", "strategy", false),
            served("auto-route-connections mode", "Invalid mode: ", ". Valid: full, labels-only, terminals-only",
                    "auto-route-connections", "mode", false),
            served("auto-route-connections labelPolicy", "Invalid labelPolicy: ", "", "auto-route-connections",
                    "labelPolicy", false),
            served("layout-within-group arrangement", "Invalid arrangement: ", ". Valid values: row, column, grid.",
                    "layout-within-group", "arrangement", false),
            served("layout-flat-view arrangement", "Invalid arrangement: ", ". Valid values: row, column, grid.",
                    "layout-flat-view", "arrangement", false),
            served("layout-flat-view sortBy", "Invalid sortBy: ", ". Valid values: name, type, layer.",
                    "layout-flat-view", "sortBy", false),
            served("layout-flat-view categoryField", "Invalid categoryField: ", ". Valid values: type, layer.",
                    "layout-flat-view", "categoryField", false),
            served("arrange-groups arrangement", "Invalid arrangement: ", ". Valid values: grid, row, column, topology.",
                    "arrange-groups", "arrangement", false),
            own("create-view connectionRouterType", "Invalid connectionRouterType: ", ". Valid values: manual, manhattan",
                    "create-view", "connectionRouterType", false),
            served("optimize-group-order arrangement", "Invalid arrangement ", MUST_BE, "optimize-group-order",
                    "arrangement", false),
            new Site("optimize-group-order groupArrangements value", "Invalid arrangement ",
                    " for group '" + GROUP + "'" + MUST_BE, false,
                    (t, v) -> t.groupArrangement(GROUP, v)),
            new Site("optimize-group-order groupArrangements key", "Invalid arrangement 'diagonal' for group ",
                    MUST_BE, false, (t, v) -> t.groupArrangement(v, "diagonal")));

    /** A site on a tool this test registers; a list-valued argument is sent as a one-entry list. */
    private static Site own(String name, String prefix, String suffix, String tool, String key, boolean list) {
        return new Site(name, prefix, suffix, true, (t, v) -> t.refusedOwn(tool, key, list ? List.of(v) : v));
    }

    /** A list-valued site that reads every string entry, blank or not. */
    private static Site reaching(String name, String prefix, String suffix, String tool, String key) {
        return new Site(name, prefix, suffix, false, (t, v) -> t.refusedOwn(tool, key, List.of(v)));
    }

    /** A site on a tool the fixture's view handlers register. */
    private static Site served(String name, String prefix, String suffix, String tool, String key, boolean list) {
        return new Site(name, prefix, suffix, true, (t, v) -> t.refusedServed(tool, key, list ? List.of(v) : v));
    }

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-other-caller-value-echo");
        f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 60);
        f.group(f.view, GROUP, 300, 300, 300, 200);
        ResponseFormatter formatter = new ResponseFormatter();
        SessionManager sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        new SearchHandler(f.accessor, formatter, registry, sessions).registerTools();
        new TraversalHandler(f.accessor, formatter, registry, sessions).registerTools();
        new ModelQueryHandler(f.accessor, formatter, registry, sessions).registerTools();
        new FolderHandler(f.accessor, formatter, registry, sessions).registerTools();
        new SessionHandler(sessions, formatter, registry).registerTools();
        new SpecializationHandler(f.accessor, formatter, registry, sessions).registerTools();
        new ElementCreationHandler(f.accessor, formatter, registry, sessions).registerTools();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- an ordinary value reads exactly as before, on every site ------------------------------

    /** What a row expects in a field the refusal leaves out. */
    private static final String NONE = "";
    private static final String LAYERS = "Valid layers: Business, Application, Technology, Physical, "
            + "Strategy, Motivation, Implementation & Migration";
    private static final String EXCLUDE_FIELDS_LIST = "connectionRouterType, connections, documentation, "
            + "folderPath, groups, images, layer, nonArchimateConnections, notes, properties, specialization, "
            + "type, viewReferences, viewpointType, visualMetadata";
    private static final String EXCLUDE_FIELDS = "Valid exclude fields: " + EXCLUDE_FIELDS_LIST;
    private static final String FORMATS = "Valid formats: json, graph, summary";
    private static final String VIEW_FORMATS = "Valid formats: json, graph, summary, tree";
    private static final String RELATIONSHIP_TYPES = "AccessRelationship, AggregationRelationship, "
            + "AssignmentRelationship, AssociationRelationship, CompositionRelationship, FlowRelationship, "
            + "InfluenceRelationship, RealizationRelationship, ServingRelationship, SpecializationRelationship, "
            + "TriggeringRelationship";
    private static final String CONCEPT_TYPES = "Use a valid concrete EClass name, e.g., 'Node', 'BusinessActor', "
            + "'ApplicationComponent', 'FlowRelationship', 'ServingRelationship', 'AndJunction'";

    @Test
    public void shouldRepeatAnOrdinaryValueAsBefore_onEverySite() {
        // Each row: the site, the call, and the whole message, details and suggestion it answered before.
        List<String> wrong = new ArrayList<>();
        expect(wrong, "search-elements type", ownError("search-elements", "type", "Bogus"),
                "Invalid element type: 'Bogus'",
                "The 'type' parameter must be a valid ArchiMate element type name "
                        + "(e.g., ApplicationComponent, BusinessProcess, Node)",
                "Use get-model-info to see all available element types in this model");
        expect(wrong, "search-elements layer", ownError("search-elements", "layer", "Bogus"),
                "Invalid layer: 'Bogus'", "The 'layer' parameter must be a valid ArchiMate layer name", LAYERS);
        expect(wrong, "search-elements exclude", ownError("search-elements", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "search-elements format", ownError("search-elements", "format", "xml"),
                "Invalid format: 'xml'", NONE, FORMATS);
        expect(wrong, "search-relationships type", ownError("search-relationships", "type", "Bogus"),
                "Invalid relationship type: 'Bogus'",
                "The 'type' parameter must be a valid ArchiMate relationship type name",
                "Valid types: " + RELATIONSHIP_TYPES);
        expect(wrong, "search-relationships sourceLayer", ownError("search-relationships", "sourceLayer", "Bogus"),
                "Invalid sourceLayer: 'Bogus'", "The 'sourceLayer' parameter must be a valid ArchiMate layer name",
                LAYERS);
        expect(wrong, "search-relationships targetLayer", ownError("search-relationships", "targetLayer", "Bogus"),
                "Invalid targetLayer: 'Bogus'", "The 'targetLayer' parameter must be a valid ArchiMate layer name",
                LAYERS);
        expect(wrong, "search-relationships exclude",
                ownError("search-relationships", "exclude", List.of("bogus")), "Invalid exclude field: 'bogus'",
                NONE, EXCLUDE_FIELDS);
        expect(wrong, "search-relationships format", ownError("search-relationships", "format", "xml"),
                "Invalid format: 'xml'", NONE, FORMATS);
        expect(wrong, "get-relationships format", ownError("get-relationships", "format", "xml"),
                "Invalid format: 'xml'", NONE, FORMATS);
        expect(wrong, "get-relationships exclude", ownError("get-relationships", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "get-relationships excludeTypes",
                ownError("get-relationships", "excludeTypes", List.of("Bogus")),
                "Invalid relationship type: 'Bogus'", NONE, "Valid relationship types: " + RELATIONSHIP_TYPES);
        expect(wrong, "get-relationships includeTypes",
                ownError("get-relationships", "includeTypes", List.of("Bogus")),
                "Invalid relationship type: 'Bogus'", NONE, "Valid relationship types: " + RELATIONSHIP_TYPES);
        expect(wrong, "get-relationships filterLayer", ownError("get-relationships", "filterLayer", "Bogus"),
                "Invalid layer: 'Bogus'", NONE, LAYERS);
        expect(wrong, "get-views exclude", servedError("get-views", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "get-views format", servedError("get-views", "format", "xml"),
                "Invalid format: 'xml'", NONE, VIEW_FORMATS);
        expect(wrong, "get-view-contents format", servedError("get-view-contents", "format", "xml"),
                "Invalid format: 'xml'", NONE, VIEW_FORMATS);
        expect(wrong, "get-view-contents exclude", servedError("get-view-contents", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "get-element exclude", ownError("get-element", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "get-folders exclude", ownError("get-folders", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "get-folder-tree exclude", ownError("get-folder-tree", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'", NONE, EXCLUDE_FIELDS);
        expect(wrong, "set-session-filter type", ownError("set-session-filter", "type", "Bogus"),
                "Invalid element type: 'Bogus'", NONE,
                "Valid types: ApplicationComponent, BusinessProcess, Node, Device, Capability, Stakeholder, "
                        + "WorkPackage, and more. Use get-model-info to see all available element types.");
        expect(wrong, "set-session-filter layer", ownError("set-session-filter", "layer", "Bogus"),
                "Invalid layer: 'Bogus'", NONE, LAYERS);
        expect(wrong, "set-session-filter fields", ownError("set-session-filter", "fields", "bogus"),
                "Invalid fields preset: 'bogus'. Valid presets: minimal, standard, full", NONE,
                "Valid presets: minimal, standard, full");
        expect(wrong, "set-session-filter exclude", ownError("set-session-filter", "exclude", List.of("bogus")),
                "Invalid exclude field: 'bogus'. Valid fields: " + EXCLUDE_FIELDS_LIST, NONE, EXCLUDE_FIELDS);
        expect(wrong, "auto-layout-and-route mode", servedError("auto-layout-and-route", "mode", "bogus"),
                "Invalid mode: 'bogus'", "mode must be one of: auto, grouped.",
                "Use mode: \"grouped\" for views with groups, or omit for ELK layout.");
        expect(wrong, "auto-layout-and-route targetRating",
                servedError("auto-layout-and-route", "targetRating", "bogus"), "Invalid targetRating: 'bogus'",
                "targetRating must be one of: excellent, good, fair. 'poor' and 'not-applicable' are not valid targets.",
                "Use targetRating: \"good\" for typical quality iteration.");
        for (String tool : List.of("create-specialization", "update-specialization", "delete-specialization",
                "get-specialization-usage")) {
            expect(wrong, tool + " conceptType", ownError(tool, "conceptType", "Bogus"),
                    "Unknown ArchiMate concept type: 'Bogus'", NONE, CONCEPT_TYPES);
        }
        expect(wrong, "auto-route-connections strategy", servedError("auto-route-connections", "strategy", "bogus"),
                "Invalid strategy: 'bogus'. Valid: orthogonal, clear", NONE, NONE);
        expect(wrong, "auto-route-connections mode", servedError("auto-route-connections", "mode", "bogus"),
                "Invalid mode: 'bogus'. Valid: full, labels-only, terminals-only", NONE, NONE);
        for (String tool : List.of("auto-route-connections", "auto-layout-and-route")) {
            expect(wrong, tool + " labelPolicy", servedError(tool, "labelPolicy", "bogus"),
                    "Invalid labelPolicy: 'bogus'", NONE, "Use one of: keep, auto-hide-on-collision");
        }
        expect(wrong, "layout-within-group arrangement", servedError("layout-within-group", "arrangement", "bogus"),
                "Invalid arrangement: 'bogus'. Valid values: row, column, grid.", NONE, NONE);
        expect(wrong, "layout-flat-view arrangement", servedError("layout-flat-view", "arrangement", "bogus"),
                "Invalid arrangement: 'bogus'. Valid values: row, column, grid.", NONE, NONE);
        expect(wrong, "layout-flat-view sortBy", servedError("layout-flat-view", "sortBy", "bogus"),
                "Invalid sortBy: 'bogus'. Valid values: name, type, layer.", NONE, NONE);
        expect(wrong, "layout-flat-view categoryField", servedError("layout-flat-view", "categoryField", "bogus"),
                "Invalid categoryField: 'bogus'. Valid values: type, layer.", NONE, NONE);
        expect(wrong, "arrange-groups arrangement", servedError("arrange-groups", "arrangement", "bogus"),
                "Invalid arrangement: 'bogus'. Valid values: grid, row, column, topology.", NONE, NONE);
        expect(wrong, "create-view connectionRouterType", ownError("create-view", "connectionRouterType", "bogus"),
                "Invalid connectionRouterType: 'bogus'. Valid values: manual, manhattan", NONE, NONE);
        expect(wrong, "update-view connectionRouterType",
                servedError("update-view", "connectionRouterType", "bogus"),
                "Invalid connectionRouterType: 'bogus'. Valid values: manual, manhattan", NONE, NONE);
        expect(wrong, "optimize-group-order arrangement", servedError("optimize-group-order", "arrangement", "bogus"),
                "Invalid arrangement 'bogus'. Must be 'row', 'column', or 'grid'.", NONE, NONE);
        expect(wrong, "optimize-group-order groupArrangements",
                error(f.serve("optimize-group-order", groupArrangementArgs(GROUP, "bogus"))),
                "Invalid arrangement 'bogus' for group 'grp-1'. Must be 'row', 'column', or 'grid'.", NONE, NONE);
        assertTrue(wrong.size() + " sites changed:\n" + String.join("\n", wrong), wrong.isEmpty());
    }

    /** Records each field of {@code error} that differs from what the row expects; {@link #NONE} is an absent field. */
    private static void expect(List<String> wrong, String site, JsonNode error, String message, String details,
            String suggestion) {
        if (!ErrorCode.INVALID_PARAMETER.name().equals(error.path("code").asText())) {
            wrong.add(site + ": code " + error.path("code").asText());
        }
        if (!message.equals(error.path("message").asText())) {
            wrong.add(site + ": message " + visible(error.path("message").asText()));
        }
        if (!details.equals(error.path("details").asText())) {
            wrong.add(site + ": details " + visible(error.path("details").asText()));
        }
        if (!suggestion.equals(error.path("suggestedCorrection").asText())) {
            wrong.add(site + ": suggestion " + visible(error.path("suggestedCorrection").asText()));
        }
    }

    /** The concept-type refusals past the unknown-name one quote a package name, and keep their texts. */
    @Test
    public void shouldKeepTheConceptTypeRefusalTexts_forPackageNames() {
        assertEquals("'FolderType' is not an EClass", refusedOwn("create-specialization", "conceptType", "FolderType"));
        assertEquals("'DiagramModelNote' is not an ArchiMate concept type",
                refusedOwn("create-specialization", "conceptType", "DiagramModelNote"));
        assertEquals("'ArchimateRelationship' is an abstract concept type and cannot have specializations",
                refusedOwn("create-specialization", "conceptType", "ArchimateRelationship"));
    }

    // ---- the bound -----------------------------------------------------------------------------

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inAFormat() {
        String msg = refusedOwn("search-elements", "format", "a".repeat(39) + EMOJI + "zz");

        assertEquals("Invalid format: '" + "a".repeat(39) + "' (the start of 43 characters)", msg);
    }

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inARouterType() {
        String msg = refusedOwn("create-view", "connectionRouterType", "a".repeat(39) + EMOJI + "zz");

        assertEquals("Invalid connectionRouterType: '" + "a".repeat(39)
                + "' (the start of 43 characters). Valid values: manual, manhattan", msg);
    }

    @Test
    public void shouldRepeatOnlyTheStartOfALongExcludeField_andSaySo() {
        String msg = refusedOwn("get-element", "exclude", List.of("q".repeat(500)));

        assertEquals("Invalid exclude field: '" + "q".repeat(40) + "' (the start of 500 characters)", msg);
    }

    // ---- the escapes ---------------------------------------------------------------------------

    @Test
    public void shouldEscapeControlsQuotesAndBackslashes_inASessionFilter_andKeepItsSuggestion() {
        JsonNode error = ownError("set-session-filter", "type", "a\nb\tc\u0000d'e\\f\rg");

        assertEquals("Invalid element type: 'a\\nb\\tc\\u0000d\\'e\\\\f\\rg'", error.path("message").asText());
        assertTrue(error.path("suggestedCorrection").asText().startsWith("Valid types: ApplicationComponent"));
    }

    /**
     * The suggestion follows the parameter that failed, never words in the value: a layer that reads like a
     * preset, an exclude field that reads like a type, a type that reads like an exclude field.
     */
    @Test
    public void shouldSuggestForTheParameterThatFailed_notForWordsInTheValue_inASessionFilter() {
        List<String> wrong = new ArrayList<>();
        expect(wrong, "layer naming a preset", ownError("set-session-filter", "layer", "fields preset"),
                "Invalid layer: 'fields preset'", NONE, LAYERS);
        expect(wrong, "exclude naming a type", ownError("set-session-filter", "exclude", List.of("element type")),
                "Invalid exclude field: 'element type'. Valid fields: " + EXCLUDE_FIELDS_LIST, NONE, EXCLUDE_FIELDS);
        expect(wrong, "type naming an exclude field", ownError("set-session-filter", "type", "exclude field"),
                "Invalid element type: 'exclude field'", NONE,
                "Valid types: ApplicationComponent, BusinessProcess, Node, Device, Capability, Stakeholder, "
                        + "WorkPackage, and more. Use get-model-info to see all available element types.");
        expect(wrong, "fields naming a type", ownError("set-session-filter", "fields", "element type"),
                "Invalid fields preset: 'element type'. Valid presets: minimal, standard, full", NONE,
                "Valid presets: minimal, standard, full");
        assertEquals("every refusal keeps its own suggestion", List.of(), wrong);
    }

    @Test
    public void shouldEscapeALoneSurrogateTheCallerSent_inAStrategy() {
        assertEquals("Invalid strategy: '\\uD83D'. Valid: orthogonal, clear",
                refusedServed("auto-route-connections", "strategy", "\uD83D"));
    }

    @Test
    public void shouldEscapeAGroupKey_asWellAsItsValue() {
        assertEquals("Invalid arrangement 'diagonal' for group 'g\\n1'" + MUST_BE, groupArrangement("g\n1", "diagonal"));
        assertEquals("Invalid arrangement 'x\\ty' for group 'grp-1'" + MUST_BE, groupArrangement(GROUP, "x\ty"));
    }

    @Test
    public void shouldEscapeABlankRelationshipTypeTheFilterStillReads() {
        assertEquals("Invalid relationship type: '\\t'", refusedOwn("get-relationships", "excludeTypes", List.of("\t")));
    }

    // ---- bulk ----------------------------------------------------------------------------------

    @Test
    public void shouldEscapeAConceptType_inBulk() {
        assertEquals("Unknown ArchiMate concept type: 'a\\nb'",
                bulkRow("create-specialization", "conceptType", "a\nb"));
    }

    @Test
    public void shouldBoundARouterType_inABulkCreateView() {
        assertEquals("Invalid connectionRouterType: '" + "a".repeat(39)
                + "' (the start of 43 characters). Valid values: manual, manhattan",
                bulkRow("create-view", "connectionRouterType", "a".repeat(39) + EMOJI + "zz"));
    }

    @Test
    public void shouldEscapeARouterType_inABulkUpdateView() {
        assertEquals("Invalid connectionRouterType: 'a\\nb'. Valid values: manual, manhattan",
                bulkRow("update-view", "connectionRouterType", "a\nb"));
    }

    // ---- the invariants, over every site -------------------------------------------------------

    @Test
    public void everyRefusal_shouldRepeatTheCallersStart_wellFormed() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            for (Site site : SITES) {
                if (site.blankIsAbsent() && value.isBlank()) {
                    continue; // read as an absent argument there, so this site never sees it
                }
                String msg = site.reader().refuse(this, value);
                checked++;
                String why = whyBroken(site.prefix(), site.suffix(), value, msg);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
        }
        long blank = NASTY.stream().filter(String::isBlank).count();
        long blankSkipping = SITES.stream().filter(Site::blankIsAbsent).count();
        assertEquals(40, SITES.size());
        assertEquals(36, blankSkipping);
        assertEquals(NASTY.size() * SITES.size() - blank * blankSkipping, checked);
        assertTrue(broken.size() + " malformed refusals:\n" + String.join("\n", broken), broken.isEmpty());
    }

    @Test
    public void everyBulkRefusal_shouldRepeatTheCallersStart_wellFormed() {
        record BulkSite(String op, String key, String prefix, String suffix) {}
        List<BulkSite> sites = List.of(
                new BulkSite("create-specialization", "conceptType", "Unknown ArchiMate concept type: ", ""),
                new BulkSite("update-specialization", "conceptType", "Unknown ArchiMate concept type: ", ""),
                new BulkSite("delete-specialization", "conceptType", "Unknown ArchiMate concept type: ", ""),
                new BulkSite("create-view", "connectionRouterType", "Invalid connectionRouterType: ",
                        ". Valid values: manual, manhattan"),
                new BulkSite("update-view", "connectionRouterType", "Invalid connectionRouterType: ",
                        ". Valid values: manual, manhattan"));
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            if (value.isBlank()) {
                continue; // bulk reads a blank value as absent on all five
            }
            for (BulkSite site : sites) {
                String msg = bulkRow(site.op(), site.key(), value);
                checked++;
                String why = whyBroken(site.prefix(), site.suffix(), value, msg);
                if (why != null) {
                    broken.add(site.op() + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
        }
        assertEquals(NASTY.stream().filter(v -> !v.isBlank()).count() * sites.size(), checked);
        assertTrue(broken.size() + " malformed refusals:\n" + String.join("\n", broken), broken.isEmpty());
    }

    // ---- no refusal quotes a caller's value by hand ---------------------------------------------

    /**
     * The lines of the shipped source that still put a value between hand-written quotes after a colon, each
     * repeating text the server holds rather than text a caller sent: a view name and two relationship ends
     * from the model, an extension derived from a file, two property keys built from model names, a tool name
     * registered at start-up, and two image values whose refusal is caught and answered elsewhere.
     */
    private static final List<String> NOT_CALLER_TEXT = List.of(
            "net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java: String base = verb + \" \" + type + \": '\" "
                    + "+ sourceDisplay + \"' \u2192 '\" + targetDisplay + \"'\";",
            "net/vheerden/archi/mcp/model/ConceptMetadata.java: \"conflicting property key: '\" + prefixed + \"'\",",
            "net/vheerden/archi/mcp/model/ConceptMetadata.java: \"source key: '\" + sourceKey + \"'\",",
            "net/vheerden/archi/mcp/model/ImageOperations.java: \"derived extension: '\" + ext + \"'\",",
            "net/vheerden/archi/mcp/model/ImageParams.java: \"Invalid image position: '\" + position "
                    + "+ \"'. Valid values: \" + POSITION_MAP.keySet());",
            "net/vheerden/archi/mcp/model/ImageParams.java: \"Invalid showIcon value: '\" + showIcon "
                    + "+ \"'. Valid values: \" + SHOW_ICON_MAP.keySet());",
            "net/vheerden/archi/mcp/registry/CommandRegistry.java: \"Duplicate MCP tool name: '\" + toolName "
                    + "+ \"' is already registered\");",
            "net/vheerden/archi/mcp/response/SummaryFormatter.java: return \"1 view\" + contextPart + \": '\" "
                    + "+ v.name() + \"'\"");

    @Test
    public void shouldQuoteNoCallersValueByHand_anywhereInTheShippedSource() throws IOException {
        Path root = locateRepoDirectory("net.vheerden.archi.mcp/src");
        Pattern byHand = Pattern.compile(": '\" *\\+");
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                files++;
                String name = root.relativize(file).toString().replace('\\', '/');
                for (String line : Files.readAllLines(file)) {
                    if (byHand.matcher(line).find()) {
                        found.add(name + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue("scanned only " + files + " files under " + root, files > 100);
        assertEquals("a refusal that repeats a caller's value goes through CallerText.quoted", NOT_CALLER_TEXT,
                found.stream().sorted().toList());
    }

    /** A repo-relative directory, found by walking up from the working directory. */
    private static Path locateRepoDirectory(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(relative))) {
                return dir.resolve(relative);
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " from " + Path.of("").toAbsolutePath());
    }

    // ---- harness -------------------------------------------------------------------------------

    /**
     * Null when the refusal keeps the site's own text, holds nothing malformed, marks a cut value as partial,
     * and repeats exactly what it should: read back through its escapes, the quoted text is the whole value
     * when it is at most the bound, and otherwise its first {@link CallerText#MAX_ECHOED} units, one fewer
     * when the last of those starts a surrogate pair.
     */
    private static String whyBroken(String prefix, String suffix, String value, String msg) {
        String why = whyMalformed(msg);
        if (why != null) {
            return why;
        }
        boolean cut = value.length() > CallerText.MAX_ECHOED;
        String marker = cut ? " (the start of " + value.length() + " characters)" : "";
        String close = "'" + marker + suffix;
        if (!msg.startsWith(prefix + "'") || !msg.endsWith(close)
                || msg.length() < prefix.length() + 1 + close.length()) {
            return cut ? "surrounding text changed, or a cut value not marked as partial" : "surrounding text changed";
        }
        String echoed = unescape(msg.substring(prefix.length() + 1, msg.length() - close.length()));
        int kept = !cut ? value.length()
                : Character.isHighSurrogate(value.charAt(CallerText.MAX_ECHOED - 1))
                        ? CallerText.MAX_ECHOED - 1 : CallerText.MAX_ECHOED;
        return echoed.equals(value.substring(0, kept)) ? null
                : "repeats " + visible(echoed) + " instead of the first " + kept + " units";
    }

    /** The caller's text back from a refusal's escapes: a backslash before a quote or backslash, n, r, t or uXXXX. */
    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** The arguments each tool needs besides the one under test, so that argument is what gets refused. */
    private Map<String, Object> base(String tool) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (tool) {
            case "search-elements", "search-relationships" -> args.put("query", "");
            case "get-relationships" -> args.put("elementId", "el-a");
            case "get-element" -> args.put("id", "el-a");
            case "create-specialization", "delete-specialization", "get-specialization-usage" ->
                    args.put("name", "S");
            case "update-specialization" -> {
                args.put("name", "S");
                args.put("newName", "T");
            }
            case "create-view" -> args.put("name", "V");
            case "get-view-contents", "update-view", "auto-layout-and-route", "auto-route-connections",
                    "optimize-group-order" -> args.put("viewId", f.view.getId());
            case "layout-within-group" -> {
                args.put("viewId", f.view.getId());
                args.put("groupViewObjectId", GROUP);
            }
            case "layout-flat-view", "arrange-groups" -> {
                args.put("viewId", f.view.getId());
                args.put("arrangement", "row");
            }
            default -> { }
        }
        return args;
    }

    private String refusedOwn(String tool, String key, Object value) {
        return ownError(tool, key, value).path("message").asText();
    }

    private String refusedServed(String tool, String key, Object value) {
        return servedError(tool, key, value).path("message").asText();
    }

    private JsonNode ownError(String tool, String key, Object value) {
        Map<String, Object> args = base(tool);
        args.put(key, value);
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow()
                .callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return error((Boolean.TRUE.equals(result.isError()) ? "ERROR " : "") + text);
    }

    private JsonNode servedError(String tool, String key, Object value) {
        Map<String, Object> args = base(tool);
        args.put(key, value);
        return error(f.serve(tool, args));
    }

    private Map<String, Object> groupArrangementArgs(String group, String arrangement) {
        Map<String, Object> arrangements = new LinkedHashMap<>();
        arrangements.put(group, arrangement);
        Map<String, Object> args = base("optimize-group-order");
        args.put("groupArrangements", arrangements);
        return args;
    }

    private String groupArrangement(String group, String arrangement) {
        return error(f.serve("optimize-group-order", groupArrangementArgs(group, arrangement)))
                .path("message").asText();
    }

    /** The decoded {@code error} of a refused handler call, after checking it is a caller error. */
    private static JsonNode error(String out) {
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        try {
            JsonNode error = JSON.readTree(out.substring("ERROR ".length())).path("error");
            assertEquals(out, ErrorCode.INVALID_PARAMETER.name(), error.path("code").asText());
            return error;
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + out, e);
        }
    }

    private String bulkRow(String op, String key, String value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "S");
        params.put("newName", "T");
        params.put("viewId", f.view.getId());
        params.put(key, value);
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION,
                    List.of(new BulkOperation(op, params)), null, false);
            fail("expected a refusal, got allSucceeded=" + result.allSucceeded());
            return null;
        } catch (BulkValidationException e) {
            List<BulkOperationFailure> rows = e.getFailures();
            assertEquals("one failure row: " + rows, 1, rows.size());
            assertEquals(ErrorCode.INVALID_PARAMETER.name(), rows.get(0).errorCode());
            return rows.get(0).message();
        }
    }
}
