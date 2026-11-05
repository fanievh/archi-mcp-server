package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.whyMalformed;
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
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pins how a refusal repeats an id, type or path a caller sent that the model does not hold, in the
 * refusals that repeat it without quotes ({@code View not found: ghost-1}). An ordinary id still reads
 * bare, byte for byte as before; one that needs escaping or cutting reads as {@link CallerText#quoted}
 * repeats it, so the reader can see where it starts and ends, and nothing malformed reaches the message.
 *
 * <p>Every call runs through the tools as the server registers them, and every text is read after
 * decoding the JSON response.</p>
 */
public class BareCallerIdEchoTest {

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("model-bare-caller-id-echo");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    // ---- a bare id stays bare while it is ordinary ---------------------------------------------------

    /** One site that repeats a caller's id without quotes. */
    private record BareSite(String name, String prefix, String suffix, boolean blankIsAbsent, Reader reader) {}

    private interface Reader {
        String read(EchoToolHarness harness, String value);
    }

    private static final List<BareSite> BARE_SITES = List.of(
            new BareSite("auto-route-connections viewId", "View not found: ", "", true,
                    (t, v) -> t.message("auto-route-connections", "viewId", v)),
            new BareSite("layout-flat-view viewId", "View not found: ", "", true,
                    (t, v) -> t.message("layout-flat-view", "viewId", v, "arrangement", "row")),
            new BareSite("arrange-groups viewId", "View not found: ", "", true,
                    (t, v) -> t.message("arrange-groups", "viewId", v, "arrangement", "grid")),
            new BareSite("update-element id", "Element not found: ", "", true,
                    (t, v) -> t.message("update-element", "id", v, "name", "N")),
            new BareSite("delete-relationship relationshipId", "Relationship not found: ", "", true,
                    (t, v) -> t.message("delete-relationship", "relationshipId", v)),
            new BareSite("move-to-folder objectId", "Object not found: ",
                    ". The ID must refer to an element, relationship, view, or folder.", true,
                    (t, v) -> t.message("move-to-folder", "objectId", v, "targetFolderId",
                            t.f.model.getFolder(FolderType.APPLICATION).getId())),
            new BareSite("move-to-folder targetFolderId", "Target folder not found: ", "", true,
                    (t, v) -> t.message("move-to-folder", "objectId", "el-a", "targetFolderId", v)),
            new BareSite("create-relationship sourceId", "Source element not found: ", "", true,
                    (t, v) -> t.message("create-relationship", "type", "AssociationRelationship", "sourceId", v,
                            "targetId", "el-a")),
            new BareSite("create-relationship targetId", "Target element not found: ", "", true,
                    (t, v) -> t.message("create-relationship", "type", "AssociationRelationship", "sourceId", "el-a",
                            "targetId", v)),
            new BareSite("create-element type", "Invalid ArchiMate element type: ", "", true,
                    (t, v) -> t.message("create-element", "type", v, "name", "N")),
            new BareSite("update-view-object viewObjectId", "View object not found: ", "", true,
                    (t, v) -> t.message("update-view-object", "viewObjectId", v, "x", 1)),
            new BareSite("add-view-reference-to-view referencedViewId",
                    "Referenced view not found or is not an ArchiMate view: ", "", true,
                    (t, v) -> t.message("add-view-reference-to-view", "viewId", t.f.view.getId(),
                            "referencedViewId", v)),
            new BareSite("bulk update-element id", "Element not found: ", "", true,
                    (t, v) -> t.bulkRow("update-element", Map.of("id", v, "name", "N"), null).message()));

    @Test
    public void everyBareEcho_shouldBeTheIdWhenOrdinary_andQuotedOtherwise() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            for (BareSite site : BARE_SITES) {
                if (site.blankIsAbsent() && value.isBlank()) {
                    continue;
                }
                String text = site.reader().read(h, value);
                checked++;
                String why = whyBrokenBare(site.prefix(), site.suffix(), value, text);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(text));
                }
            }
        }
        long blank = NASTY.stream().filter(String::isBlank).count();
        assertEquals(13, BARE_SITES.size());
        assertEquals(NASTY.size() * BARE_SITES.size() - blank * BARE_SITES.size(), checked);
        assertTrue(broken.size() + " malformed echoes:\n" + String.join("\n", broken), broken.isEmpty());
    }

    @Test
    public void shouldKeepAnOrdinaryIdBare_onEveryBareSite() {
        List<String> wrong = new ArrayList<>();
        for (BareSite site : BARE_SITES) {
            String text = site.reader().read(h, "ghost-1");
            if (!(site.prefix() + "ghost-1" + site.suffix()).equals(text)) {
                wrong.add(site.name() + ": " + visible(text));
            }
        }
        String archiId = "id-0123456789abcdef0123456789abcdef";
        String archiText = h.message("auto-route-connections", "viewId", archiId);
        if (!("View not found: " + archiId).equals(archiText)) {
            wrong.add("a 35-unit Archi id: " + visible(archiText));
        }
        assertTrue(wrong.size() + " sites changed:\n" + String.join("\n", wrong), wrong.isEmpty());
    }

    @Test
    public void shouldQuoteAPositionEntrysId_inTheWrapperAndItsCause() {
        JsonNode error = h.servedError("apply-positions", "viewId", h.f.view.getId(), "positions",
                List.of(Map.of("viewObjectId", "a\nb", "x", 1, "y", 1)));
        assertEquals("Position entry [0] (viewObjectId='a\\nb'): View object not found: 'a\\nb'",
                error.path("message").asText());
        JsonNode ordinary = h.servedError("apply-positions", "viewId", h.f.view.getId(), "positions",
                List.of(Map.of("viewObjectId", "ghost-1", "x", 1, "y", 1)));
        assertEquals("Position entry [0] (viewObjectId='ghost-1'): View object not found: ghost-1",
                ordinary.path("message").asText());
    }

    @Test
    public void everyPositionEntrysId_shouldBeQuotedInTheWrapper_andBareOrQuotedInTheCause() {
        String wrapperStart = "Position entry [0] (viewObjectId=";
        String causeStart = "): View object not found: ";
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            if (value.isBlank()) {
                continue;
            }
            String text = h.servedError("apply-positions", "viewId", h.f.view.getId(), "positions",
                    List.of(Map.of("viewObjectId", value, "x", 1, "y", 1))).path("message").asText();
            checked++;
            int cut = text.lastIndexOf(causeStart);
            String why = cut < 0 ? "no cause after the wrapper"
                    : whyBroken(wrapperStart, ")", value, text.substring(0, cut + 1));
            if (why == null) {
                why = whyBrokenBare("", "", value, text.substring(cut + causeStart.length()));
            }
            if (why != null) {
                broken.add("value " + visible(value) + ": " + why + " in " + visible(text));
            }
        }
        assertEquals(NASTY.stream().filter(v -> !v.isBlank()).count(), checked);
        assertTrue(broken.size() + " malformed position entries:\n" + String.join("\n", broken), broken.isEmpty());
    }

    @Test
    public void shouldEscapeAMissingConnectionId_inTheRoutingWarnings() {
        List<String> texts = new ArrayList<>();
        collectTexts(h.success("auto-route-connections", "viewId", h.f.view.getId(), "connectionIds",
                List.of("conn-ab", "ghost-1", "c\nd")), texts);
        assertTrue(visible(texts.toString()), texts.contains("Connection not found on view: ghost-1"));
        assertTrue(visible(texts.toString()), texts.contains("Connection not found on view: 'c\\nd'"));
        assertTrue(visible(texts.toString()), texts.stream().anyMatch(
                t -> t.startsWith("2 requested connection ID(s) were not found on the view and were skipped: "
                        + "ghost-1, 'c\\nd'.")));
        // The structured warning also lists the ids as data, exactly as sent; only the sentences are checked.
        for (String text : texts) {
            if (text.contains(" not found ")) {
                assertEquals(visible(text), null, whyMalformed(text));
            }
        }
    }

    /** Every string value in a decoded response, depth first. */
    private static void collectTexts(JsonNode node, List<String> out) {
        if (node.isTextual()) {
            out.add(node.asText());
        }
        node.forEach(child -> collectTexts(child, out));
    }

    /**
     * Null when the text keeps the site's own words around the id, which reads exactly as sent when it is
     * ordinary, and otherwise exactly as {@link CallerText#quoted} repeats it.
     */
    private static String whyBrokenBare(String prefix, String suffix, String value, String text) {
        String why = whyMalformed(text);
        if (why != null) {
            return why;
        }
        boolean ordinary = value.length() <= CallerText.MAX_ECHOED && CallerText.echo(value, '\'').equals(value);
        String want = prefix + (ordinary ? value : CallerText.quoted(value)) + suffix;
        if (!want.equals(text)) {
            return ordinary ? "an ordinary id not repeated bare" : "not repeated as quoted";
        }
        return ordinary ? null : whyBroken(prefix, suffix, value, text);
    }


    /**
     * Lines that repeat an id after "not found", or a type name the model did not know, without going through
     * {@link CallerText}, and why each is not a caller's text.
     */
    private static final List<String> NOT_CALLER_IDS = List.of(
            // Ids the server prepared itself, checked again inside the command it built.
            "model/RemoveFromViewCommand.java: + \" not found in prepared parent=\" + parentId",
            // A keystore path from the plugin's preferences, logged when the TLS server cannot start.
            "server/TransportConfig.java: \"Keystore file not found: \" + keystorePath,");

    @Test
    public void shouldRepeatEveryMissingIdThroughCallerText() throws IOException {
        Pattern sameLine = Pattern.compile("not found[^\"]*\" *\\+ *[A-Za-z_(]"
                + "|Invalid ArchiMate (element|relationship) type: \" *\\+");
        Pattern endsLine = Pattern.compile("not found[^\"]*: \"( *\\+)?\\s*$");
        List<String> raw = new ArrayList<>();
        int routed = 0;
        for (Map.Entry<String, List<String>> file : EchoToolHarness.shippedCode().entrySet()) {
            List<String> lines = file.getValue();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String echo = sameLine.matcher(line).find() ? line
                        : endsLine.matcher(line).find() && i + 1 < lines.size() ? line + " " + lines.get(i + 1)
                        : null;
                if (echo == null) {
                    continue;
                }
                if (echo.contains("CallerText.")) {
                    routed++;
                } else {
                    raw.add(file.getKey() + ": " + line.strip());
                }
            }
        }
        assertEquals("a missing id is repeated through CallerText.bare", NOT_CALLER_IDS, raw.stream().sorted().toList());
        assertTrue("found only " + routed + " routed lines", routed >= 56);
    }

    @Test
    public void shouldQuoteNoLayoutEntrysIdByHand() throws IOException {
        Pattern byHand = Pattern.compile("\"='\" *\\+");
        List<String> found = EchoToolHarness.shippedCode().get("model/LayoutValidationFailures.java").stream()
                .filter(line -> byHand.matcher(line).find()).map(String::strip).toList();
        assertEquals("a layout entry's id is repeated through CallerText.quoted", List.of(), found);
    }
}
