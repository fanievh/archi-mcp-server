package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.EMOJI;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.EchoToolHarness.whyBroken;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;

import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Pins how the id, name, path, tool name and search-text echoes repeat what a caller sent, in refusals,
 * warnings, summaries and next steps: an ordinary value reads exactly as before, a long one is cut to its
 * first 40 UTF-16 units without splitting a character and says it was cut, and no control character,
 * unpaired surrogate, line separator, format character, quote or backslash reaches the text raw.
 *
 * <p>These are the echoes that were already between single quotes, and they stay quoted. The ids a refusal
 * repeats bare are pinned by {@code BareCallerIdEchoTest}.</p>
 *
 * <p>Every call runs through the tools as the server registers them, model scope included, and every
 * text is read after decoding the JSON response, so an escape asserted here is text in the response.</p>
 */
public class RemainingCallerTextEchoTest {

    private static final String FIELDS_WARNING_TAIL = ", using 'standard'. Valid presets: minimal, standard, full";
    private static final String NO_ELEMENT_FIX = "Use search-elements to find elements by name or type";
    private static final String NO_SPECIALIZATION_FIX = "Use list-specializations to see defined specializations";
    private static final String ACCESS_TYPE_TAIL = ". Valid: access, read, write, readwrite.";
    private static final String ACCESS_TYPE_FIX = "Use one of: access, read, write, readwrite (or omit to leave unchanged)";
    private static final String DIRECTION_TAIL = ". Valid: DOWN, RIGHT, UP, LEFT";
    private static final String OBJECT_TYPES_TAIL = " in 'objectTypes'. Supported: "
            + "[element, note, group]";
    private static final String NAME_RULE = " is not a legal name. A name must start with a letter or underscore "
            + "and continue with letters, digits or underscores.";
    private static final String IMAGE_TAIL = " does not resolve to an image in the model archive";
    private static final String NOT_OPEN_TAIL = " is not open in Archi. Nothing was applied.";
    private static final String NO_VIEWS_TAIL = ". Try a broader name, remove the name filter, or use search-elements "
            + "to find elements by name.";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("model-remaining-caller-text-echo");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    // ---- one quoted echo: where a reader finds it, and what surrounds it ---------------------------

    /**
     * One site that quotes a caller's value: the text it puts before and after the quoted value, whether the
     * tool reads a blank value as absent (so the site never sees one), and how to reach it.
     */
    private record Site(String name, String prefix, String suffix, boolean blankIsAbsent, Reader reader) {}

    private interface Reader {
        String read(EchoToolHarness harness, String value);
    }

    private static final List<Site> QUOTED_SITES = List.of(
            new Site("bulk-mutate tool", "Operation at index 0: Unsupported tool ",
                    ". Supported: " + BulkOperation.SUPPORTED_TOOLS_ORDERED, true,
                    (t, v) -> t.errorOf("bulk-mutate", Map.of("operations",
                            List.of(Map.of("tool", v, "params", Map.of())))).path("message").asText()),
            new Site("bulk accessor tool", "Unsupported tool ", ". Supported: " + BulkOperation.SUPPORTED_TOOLS,
                    false, (t, v) -> t.bulkRow(v, Map.of(), null).message()),
            new Site("auto-layout-and-route direction", "Invalid direction ", DIRECTION_TAIL, true,
                    (t, v) -> t.message("auto-layout-and-route", "viewId", t.f.view.getId(), "direction", v)),
            new Site("create-relationship accessType", "Invalid accessType ", ACCESS_TYPE_TAIL, false,
                    (t, v) -> t.message("create-relationship", "type", "AccessRelationship", "sourceId", "el-a",
                            "targetId", "do-1", "accessType", v)),
            new Site("update-relationship accessType", "Invalid accessType ", ACCESS_TYPE_TAIL, false,
                    (t, v) -> t.message("update-relationship", "id", "rel-acc", "accessType", v)),
            new Site("bulk create-relationship accessType", "Invalid accessType ", ACCESS_TYPE_TAIL, true,
                    (t, v) -> t.bulkRow("create-relationship", Map.of("type", "AccessRelationship", "sourceId",
                            "el-a", "targetId", "do-1", "accessType", v), null).message()),
            new Site("bulk update-relationship accessType", "Invalid accessType ", ACCESS_TYPE_TAIL, true,
                    (t, v) -> t.bulkRow("update-relationship", Map.of("id", "rel-acc", "accessType", v), null)
                            .message()),
            new Site("bulk set-view-label-expression objectTypes", "Unknown object type ", OBJECT_TYPES_TAIL,
                    false, (t, v) -> t.bulkRow("set-view-label-expression", Map.of("viewId", t.f.view.getId(),
                            "labelExpression", "${name}", "objectTypes", List.of(v)), null).message()),
            new Site("bulk as", "Operation name ", NAME_RULE, false,
                    (t, v) -> t.bulkRow("create-element", Map.of("type", "BusinessActor", "name", "x"), v)
                            .message()),
            warning("search-elements", "query", ""),
            warning("search-relationships", "query", ""),
            warning("get-views"),
            warning("get-view-contents", "viewId", "view-source"),
            warning("get-element", "id", "el-a"),
            warning("get-relationships", "elementId", "el-a"),
            warning("get-folders"),
            warning("get-folder-tree"),
            new Site("get-relationships elementId", "No element found with ID ", "", true,
                    (t, v) -> t.message("get-relationships", "elementId", v)),
            new Site("get-relationships traverse elementId", "No element found with ID ", "", true,
                    (t, v) -> t.message("get-relationships", "elementId", v, "traverse", true)),
            new Site("get-element id", "No element found with ID ", "", true,
                    (t, v) -> t.message("get-element", "id", v)),
            new Site("get-folders parentId", "No folder found with ID ", "", true,
                    (t, v) -> t.message("get-folders", "parentId", v)),
            new Site("get-folder-tree rootId", "No folder found with ID ", "", true,
                    (t, v) -> t.message("get-folder-tree", "rootId", v)),
            new Site("get-view-contents viewId", "No view found with ID ", ". The model contains 2 views.", true,
                    (t, v) -> t.message("get-view-contents", "viewId", v)),
            new Site("update-specialization name", "Specialization not found: name=", ", conceptType='BusinessActor'",
                    true, (t, v) -> t.message("update-specialization", "name", v, "conceptType", "BusinessActor",
                            "newName", "T")),
            new Site("delete-specialization name", "Specialization not found: name=", ", conceptType='BusinessActor'",
                    true, (t, v) -> t.message("delete-specialization", "name", v, "conceptType", "BusinessActor")),
            new Site("get-specialization-usage name", "Specialization not found: name=",
                    ", conceptType='BusinessActor'", true,
                    (t, v) -> t.message("get-specialization-usage", "name", v, "conceptType", "BusinessActor")),
            new Site("bulk update-specialization name", "Specialization not found: name=",
                    ", conceptType='BusinessActor'", true, (t, v) -> t.bulkRow("update-specialization",
                            Map.of("name", v, "conceptType", "BusinessActor", "newName", "T"), null).message()),
            new Site("bulk delete-specialization name", "Specialization not found: name=",
                    ", conceptType='BusinessActor'", true, (t, v) -> t.bulkRow("delete-specialization",
                            Map.of("name", v, "conceptType", "BusinessActor"), null).message()),
            new Site("add-image-to-view imagePath", "imagePath ", IMAGE_TAIL, true,
                    (t, v) -> t.message("add-image-to-view", "viewId", t.f.view.getId(), "imagePath", v)),
            new Site("bulk add-image-to-view imagePath", "imagePath ", IMAGE_TAIL, true,
                    (t, v) -> t.bulkRow("add-image-to-view", Map.of("viewId", t.f.view.getId(), "imagePath", v),
                            null).message()),
            new Site("get-element modelId", "Model ", NOT_OPEN_TAIL, true,
                    (t, v) -> t.message("get-element", "id", "el-a", "modelId", v)),
            new Site("update-element modelId", "Model ", NOT_OPEN_TAIL, true,
                    (t, v) -> t.message("update-element", "id", "el-a", "name", "N", "modelId", v)),
            new Site("search-elements summary", "No elements found matching ", ".", false,
                    (t, v) -> t.success("search-elements", "query", v, "format", "summary").path("summary").asText()),
            new Site("get-views summary", "No views found matching ", ".", true,
                    (t, v) -> t.success("get-views", "name", v, "format", "summary").path("summary").asText()),
            new Site("search-relationships summary", "No relationships found matching ", ".", false,
                    (t, v) -> t.success("search-relationships", "query", v, "format", "summary")
                            .path("summary").asText()),
            new Site("get-views next step", "No views matching ", NO_VIEWS_TAIL, true,
                    (t, v) -> t.success("get-views", "name", v).path("nextSteps").path(0).asText()),
            new Site("search-and-create next step", "Search returned 0 results for ", " before creating", true,
                    (t, v) -> t.success("search-and-create", "query", v, "createType", "BusinessActor",
                            "createName", "N").path("nextSteps").path(2).asText()));

    /** A read tool's warning for an unknown {@code fields} preset, reached with the arguments it needs. */
    private static Site warning(String tool, Object... base) {
        return new Site(tool + " fields", "Invalid fields preset ", FIELDS_WARNING_TAIL, true, (t, v) -> {
            Object[] args = java.util.Arrays.copyOf(base, base.length + 2);
            args[base.length] = "fields";
            args[base.length + 1] = v;
            return t.success(tool, args).path("_meta").path("warning").asText();
        });
    }

    // ---- an ordinary value reads exactly as before, on every quoted site ---------------------------

    @Test
    public void shouldRepeatAnOrdinaryValueAsBefore_onEveryQuotedSite() {
        List<String> wrong = new ArrayList<>();
        expect(wrong, "bulk-mutate tool (1)", h.errorOf("bulk-mutate", Map.of("operations",
                List.of(Map.of("tool", "bogus-tool", "params", Map.of())))),
                "INVALID_PARAMETER", "Operation at index 0: Unsupported tool 'bogus-tool'. Supported: "
                        + BulkOperation.SUPPORTED_TOOLS_ORDERED,
                "", "Use a supported tool: " + BulkOperation.SUPPORTED_TOOLS);
        BulkOperationFailure tool = h.bulkRow("bogus-tool", Map.of(), null);
        expect(wrong, "bulk accessor tool (2)", tool.errorCode(), tool.message(), "", tool.suggestedCorrection(),
                "INVALID_PARAMETER", "Unsupported tool 'bogus-tool'. Supported: " + BulkOperation.SUPPORTED_TOOLS,
                "", null);
        expect(wrong, "direction (3)", h.servedError("auto-layout-and-route", "viewId", h.f.view.getId(),
                "direction", "diagonal"), "INVALID_PARAMETER", "Invalid direction 'diagonal'" + DIRECTION_TAIL, "", "");
        expect(wrong, "accessType (4)", h.servedError("create-relationship", "type", "AccessRelationship",
                "sourceId", "el-a", "targetId", "do-1", "accessType", "bogus"),
                "INVALID_PARAMETER", "Invalid accessType 'bogus'" + ACCESS_TYPE_TAIL, "", ACCESS_TYPE_FIX);
        BulkOperationFailure access = h.bulkRow("create-relationship", Map.of("type", "AccessRelationship",
                "sourceId", "el-a", "targetId", "do-1", "accessType", "bogus"), null);
        expect(wrong, "bulk accessType (4)", access.errorCode(), access.message(), "", access.suggestedCorrection(),
                "INVALID_PARAMETER", "Invalid accessType 'bogus'" + ACCESS_TYPE_TAIL, "", ACCESS_TYPE_FIX);
        BulkOperationFailure objectType = h.bulkRow("set-view-label-expression", Map.of("viewId", h.f.view.getId(),
                "labelExpression", "${name}", "objectTypes", List.of("bogus")), null);
        expect(wrong, "objectTypes (5)", objectType.errorCode(), objectType.message(), "",
                objectType.suggestedCorrection(), "INVALID_PARAMETER",
                "Unknown object type 'bogus'" + OBJECT_TYPES_TAIL, "", null);
        BulkOperationFailure label = h.bulkRow("create-element", Map.of("type", "BusinessActor", "name", "x"), "1bad");
        expect(wrong, "as (6)", label.errorCode(), label.message(), "", label.suggestedCorrection(),
                "INVALID_PARAMETER", "Operation name '1bad'" + NAME_RULE, "",
                "Rename the operation to something like 'coreBanking', then reference it as '$coreBanking.id'.");
        for (Site site : QUOTED_SITES) {
            if (site.name().endsWith(" fields")) {
                String warning = site.reader().read(h, "bogus");
                if (!("Invalid fields preset 'bogus'" + FIELDS_WARNING_TAIL).equals(warning)) {
                    wrong.add(site.name() + " (7-14): warning " + visible(warning));
                }
            }
        }
        String elements = "The model contains 3 elements total.";
        expect(wrong, "get-relationships (15)", h.servedError("get-relationships", "elementId", "nope"),
                "ELEMENT_NOT_FOUND", "No element found with ID 'nope'", "", NO_ELEMENT_FIX);
        expect(wrong, "get-relationships traverse (16)", h.servedError("get-relationships", "elementId", "nope",
                "traverse", true), "ELEMENT_NOT_FOUND", "No element found with ID 'nope'", "", NO_ELEMENT_FIX);
        expect(wrong, "get-element (17)", h.servedError("get-element", "id", "nope"),
                "ELEMENT_NOT_FOUND", "No element found with ID 'nope'", elements, NO_ELEMENT_FIX);
        String folders = "The model contains 9 root folders.";
        expect(wrong, "get-folders (18)", h.servedError("get-folders", "parentId", "nope"), "FOLDER_NOT_FOUND",
                "No folder found with ID 'nope'", folders, "Use get-folders without parentId to see root folders");
        expect(wrong, "get-folder-tree (19)", h.servedError("get-folder-tree", "rootId", "nope"), "FOLDER_NOT_FOUND",
                "No folder found with ID 'nope'", folders, "Use get-folders without parentId to see root folders");
        expect(wrong, "get-view-contents (20)", h.servedError("get-view-contents", "viewId", "nope"),
                "VIEW_NOT_FOUND", "No view found with ID 'nope'. The model contains 2 views.", "",
                "Use get-views to list available view IDs");
        String specialization = "Specialization not found: name='Nope', conceptType='BusinessActor'";
        expect(wrong, "update-specialization (21)", h.servedError("update-specialization", "name", "Nope",
                "conceptType", "BusinessActor", "newName", "T"), "OBJECT_NOT_FOUND", specialization, "",
                NO_SPECIALIZATION_FIX);
        expect(wrong, "delete-specialization (22)", h.servedError("delete-specialization", "name", "Nope",
                "conceptType", "BusinessActor"), "OBJECT_NOT_FOUND", specialization, "", NO_SPECIALIZATION_FIX);
        BulkOperationFailure deleted = h.bulkRow("delete-specialization", Map.of("name", "Nope",
                "conceptType", "BusinessActor"), null);
        expect(wrong, "bulk delete-specialization (22)", deleted.errorCode(), deleted.message(), "",
                deleted.suggestedCorrection(), "OBJECT_NOT_FOUND", specialization, "", NO_SPECIALIZATION_FIX);
        expect(wrong, "get-specialization-usage (23)", h.servedError("get-specialization-usage", "name", "Nope",
                "conceptType", "BusinessActor"), "OBJECT_NOT_FOUND", specialization, "", NO_SPECIALIZATION_FIX);
        expect(wrong, "imagePath (24)", h.servedError("add-image-to-view", "viewId", h.f.view.getId(),
                "imagePath", "images/nope.png"), "IMAGE_NOT_FOUND", "imagePath 'images/nope.png'" + IMAGE_TAIL, "",
                "Use list-model-images to browse stored images, or add-image-to-model to import a new one.");
        expect(wrong, "modelId (25)", h.servedError("get-element", "id", "el-a", "modelId", "nope"), "MODEL_NOT_OPEN",
                "Model 'nope'" + NOT_OPEN_TAIL, "Open models: 1. '' (id model-remaining-caller-text-echo)",
                "Pass the modelId of an open model (see details or get-models), or open the model in Archi first.");
        expectText(wrong, "search-elements summary (26)", h.success("search-elements", "query", "nomatch",
                "format", "summary").path("summary").asText(), "No elements found matching 'nomatch'.");
        expectText(wrong, "search-elements summary (27)", h.success("search-elements", "query", "el-a",
                "format", "summary").path("summary").asText(),
                "Found 1 element matching 'el-a': 1 ApplicationComponent. Layer: Application.");
        expectText(wrong, "get-views summary (28)", h.success("get-views", "name", "nomatch", "format", "summary")
                .path("summary").asText(), "No views found matching 'nomatch'.");
        expectText(wrong, "get-views summary (29)", h.success("get-views", "name", "Sour", "format", "summary")
                .path("summary").asText(), "1 view matching 'Sour': 'Source'.");
        expectText(wrong, "search-relationships summary (30)", h.success("search-relationships", "query", "nomatch",
                "format", "summary").path("summary").asText(), "No relationships found matching 'nomatch'.");
        expectText(wrong, "search-relationships summary (31)", h.success("search-relationships", "query", "acc",
                "format", "summary").path("summary").asText(),
                "Found 1 relationship matching 'acc': 1 AccessRelationship. Connecting 2 unique elements.");
        expectText(wrong, "get-views next step (32)", h.success("get-views", "name", "nomatch").path("nextSteps")
                .toString(), "[\"No views matching 'nomatch'" + NO_VIEWS_TAIL + "\"]");
        JsonNode created = h.success("search-and-create", "query", "nomatch", "createType", "BusinessActor",
                "createName", "N");
        expectText(wrong, "search-and-create next step (33)", created.path("nextSteps").path(2).asText(),
                "Search returned 0 results for 'nomatch' before creating");
        assertTrue(wrong.size() + " sites changed:\n" + String.join("\n", wrong), wrong.isEmpty());
    }

    private static void expect(List<String> wrong, String site, JsonNode error, String code, String message,
            String details, String suggestion) {
        expect(wrong, site, error.path("code").asText(), error.path("message").asText(),
                error.path("details").asText(), error.path("suggestedCorrection").asText(), code, message, details,
                suggestion);
    }

    /** Records each field that differs from what the row expects; an empty expected field is an absent one. */
    private static void expect(List<String> wrong, String site, String code, String message, String details,
            String suggestion, String wantCode, String wantMessage, String wantDetails, String wantSuggestion) {
        if (!wantCode.equals(code)) {
            wrong.add(site + ": code " + code);
        }
        expectText(wrong, site + ": message", message, wantMessage);
        expectText(wrong, site + ": details", details, wantDetails);
        if (wantSuggestion == null ? suggestion != null : !wantSuggestion.equals(suggestion)) {
            wrong.add(site + ": suggestion " + (suggestion == null ? "null" : visible(suggestion)));
        }
    }

    private static void expectText(List<String> wrong, String site, String actual, String expected) {
        if (!expected.equals(actual)) {
            wrong.add(site + ": " + visible(actual));
        }
    }

    // ---- the bound, the escapes, and the values that are not strings -------------------------------

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inAHandlerBuiltRefusal() {
        assertEquals("No element found with ID '" + "a".repeat(39) + "' (the start of 43 characters)",
                h.message("get-element", "id", "a".repeat(39) + EMOJI + "zz"));
    }

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inAModelId() {
        assertEquals("Model '" + "a".repeat(39) + "' (the start of 43 characters)" + NOT_OPEN_TAIL,
                h.message("get-element", "id", "el-a", "modelId", "a".repeat(39) + EMOJI + "zz"));
    }

    @Test
    public void shouldRepeatOnlyTheStartOfALongQuery_inASummary_andSaySo() {
        assertEquals("No elements found matching '" + "q".repeat(40) + "' (the start of 500 characters).",
                h.success("search-elements", "query", "q".repeat(500), "format", "summary").path("summary").asText());
    }

    @Test
    public void shouldEscapeAnImagePath() {
        assertEquals("imagePath 'images/a\\nb\\\\c\\'d.png'" + IMAGE_TAIL, h.message("add-image-to-view",
                "viewId", h.f.view.getId(), "imagePath", "images/a\nb\\c'd.png"));
    }

    @Test
    public void shouldEscapeALoneSurrogate_inAnElementIdOnTheDepthPath() {
        assertEquals("No element found with ID 'x\\uD83D'", h.message("get-relationships", "elementId", "x\uD83D"));
    }

    @Test
    public void shouldQuoteAnObjectTypeThatIsNotAString_asItsText() {
        assertEquals("Unknown object type '5'" + OBJECT_TYPES_TAIL, h.bulkRow("set-view-label-expression",
                Map.of("viewId", h.f.view.getId(), "labelExpression", "${name}", "objectTypes", List.of(5)), null)
                .message());
    }

    @Test
    public void shouldEscapeAToolName_inTheMalformedRowAndItsNextStep() {
        JsonNode root = h.call("bulk-mutate", Map.of("operations", List.of(Map.of("tool", "a\nb", "params", Map.of()))));
        assertEquals("Operation at index 0: Unsupported tool 'a\\nb'. Supported: "
                + BulkOperation.SUPPORTED_TOOLS_ORDERED, root.path("error").path("message").asText());
        assertEquals("Fix Operation at index 0: Unsupported tool 'a\\nb'.", root.path("nextSteps").path(0).asText());
    }

    @Test
    public void shouldEscapeAnOperationName_inBulk() {
        assertEquals("Operation name 'a\\nb'" + NAME_RULE,
                h.bulkRow("create-element", Map.of("type", "BusinessActor", "name", "x"), "a\nb").message());
    }

    @Test
    public void shouldEscapeAnAccessType_inABulkCreateRelationship() {
        assertEquals("Invalid accessType 'a\\tb'" + ACCESS_TYPE_TAIL, h.bulkRow("create-relationship",
                Map.of("type", "AccessRelationship", "sourceId", "el-a", "targetId", "do-1", "accessType", "a\tb"),
                null).message());
    }

    @Test
    public void shouldEscapeASpecializationName_inABulkDelete() {
        assertEquals("Specialization not found: name='a\\u0000b', conceptType='BusinessActor'",
                h.bulkRow("delete-specialization", Map.of("name", "a\u0000b", "conceptType", "BusinessActor"), null)
                        .message());
    }

    @Test
    public void shouldEscapeTheQuery_whereASummaryNamesWhatMatched() {
        IArchimateElement odd = h.f.factory.createBusinessActor();
        odd.setName("x\ny-thing");
        h.f.model.getFolder(FolderType.BUSINESS).getElements().add(odd);
        h.f.view("view-odd", "v\tw-odd");
        IArchimateRelationship rel = h.f.relationship("rel-odd", odd, odd);
        rel.setName("r\nr-link");

        assertEquals("Found 1 element matching 'x\\ny': 1 BusinessActor. Layer: Business.",
                h.success("search-elements", "query", "x\ny", "format", "summary").path("summary").asText());
        assertEquals("1 view matching 'v\\tw': 'v\tw-odd'.",
                h.success("get-views", "name", "v\tw", "format", "summary").path("summary").asText());
        assertEquals("Found 1 relationship matching 'r\\nr': 1 AssociationRelationship. Connecting 1 unique element.",
                h.success("search-relationships", "query", "r\nr", "format", "summary").path("summary").asText());
    }

    // ---- the invariants over every quoted site ----------------------------------------------------

    @Test
    public void everyQuotedEcho_shouldRepeatTheCallersStart_wellFormed() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            for (Site site : QUOTED_SITES) {
                if (site.blankIsAbsent() && value.isBlank()) {
                    continue; // read as an absent argument there, so this site never sees it
                }
                String text = site.reader().read(h, value);
                checked++;
                String why = whyBroken(site.prefix(), site.suffix(), value, text);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(text));
                }
            }
        }
        long blank = NASTY.stream().filter(String::isBlank).count();
        long blankSkipping = QUOTED_SITES.stream().filter(Site::blankIsAbsent).count();
        assertEquals(37, QUOTED_SITES.size());
        assertEquals(30, blankSkipping);
        assertEquals(NASTY.size() * QUOTED_SITES.size() - blank * blankSkipping, checked);
        assertTrue(broken.size() + " malformed echoes:\n" + String.join("\n", broken), broken.isEmpty());
    }

    // ---- no site repeats a caller's value by hand ----------------------------------------------------

    /** Caller echoes once quoted by hand, now routed through CallerText, each by the file it lived in and its literal. */
    private static final List<String[]> GONE = List.of(
            new String[] {"response/dto/BulkOperation.java", "\"Unsupported tool '\" *\\+"},
            new String[] {"model/ArchiModelAccessorImpl.java", "\"Unsupported tool '\" *\\+"},
            new String[] {"model/ElkLayoutEngine.java", "\"Invalid direction '\" *\\+"},
            new String[] {"model/RelationshipSemantics.java", "\"Invalid accessType '\" *\\+"},
            new String[] {"model/SetViewLabelExpressionCommand.java", "\"Unknown object type '\" *\\+"},
            new String[] {"model/BulkBackReferences.java", "\"Operation name '\" *\\+ *label *\\+ *\"' is not a legal"},
            new String[] {"handlers/", "\"Invalid fields preset '\" *\\+"},
            new String[] {"handlers/", "\"No element found with ID '\" *\\+"},
            new String[] {"handlers/", "\"No folder found with ID '\" *\\+"},
            new String[] {"handlers/", "\"No view found with ID '\" *\\+"},
            new String[] {"handlers/", "\"No views matching '\" *\\+"},
            new String[] {"handlers/", "\"Search returned 0 results for '\" *\\+"},
            new String[] {"model/ArchiModelAccessorImpl.java", "\"Specialization not found: name='\" *\\+"},
            new String[] {"model/ArchiModelAccessorImpl.java", "\"imagePath '\"\\s*$"},
            new String[] {"model/ModelSelection.java", "\"Model '\" *\\+"},
            new String[] {"model/", "\"'\" *\\+ *type *\\+ *\"' is not an ArchiMate element type"},
            new String[] {"model/", "\"'\" *\\+ *type *\\+ *\"' is not an ArchiMate relationship type"},
            new String[] {"model/ArchiModelAccessorImpl.java", "\"'\" *\\+ *conceptType *\\+"},
            new String[] {"response/SummaryFormatter.java", "matching '\" *\\+"});

    @Test
    public void shouldQuoteNoRoutedEchoByHand() throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> file : EchoToolHarness.shippedCode().entrySet()) {
            for (String[] gone : GONE) {
                if (!file.getKey().startsWith(gone[0])) {
                    continue;
                }
                Pattern p = Pattern.compile(gone[1]);
                for (String line : file.getValue()) {
                    if (p.matcher(line).find()) {
                        found.add(file.getKey() + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue(found.size() + " hand-quoted echoes remain:\n" + String.join("\n", found), found.isEmpty());
    }

}
