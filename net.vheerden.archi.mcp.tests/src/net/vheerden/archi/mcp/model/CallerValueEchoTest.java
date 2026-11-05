package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.EMOJI;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.whyMalformed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Pins how the styling, image, anchor and note-position refusals repeat the value a caller sent: an
 * ordinary value reads exactly as before, a long one is cut to its first 40 UTF-16 units without
 * splitting a character and says it was cut, and no control character, unpaired surrogate, line
 * separator, direction control, quote or backslash reaches the message raw. Messages are read after
 * decoding the JSON response, so an escape asserted here is text in the message.
 *
 * <p>Before, each of these refusals repeated the value whole and raw: a newline split the DEBUG log
 * entry, a lone surrogate reached the HTTP response as bytes that are not UTF-8, and a long value was
 * repeated in full.</p>
 */
public class CallerValueEchoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ViewContentsFixture f;

    /**
     * One refusal site: how a value reaches it and the text its refusal puts around the value. Where the
     * handler reads the argument as absent when it is blank, a blank value never reaches the site, and bulk
     * reads it the same way. A site with {@code inBulk} is also read by {@code bulk-mutate}'s
     * {@code update-view-object}, under its name.
     */
    private record Site(String name, String prefix, String suffix, boolean blankIsAbsent, boolean inBulk,
            Reader reader) {}

    private interface Reader {
        String refuse(CallerValueEchoTest test, String value);
    }

    private static final String NOTE_SUFFIX = ". Must be 'above-content' or 'below-content'";

    private static final List<Site> SITES = List.of(
            styling("figureType", true), styling("textAlignment", true), styling("verticalTextAlignment", true),
            styling("fontStyle", true), styling("lineStyle", false), styling("gradient", false),
            styling("borderType", false),
            new Site("fillColor", "Invalid fillColor format: ", "", false, true,
                    (t, v) -> t.updateObject("fillColor", v)),
            new Site("imagePosition", "Invalid image position: ", "", true, true,
                    (t, v) -> t.updateObject("imagePosition", v)),
            new Site("showIcon", "Invalid showIcon value: ", "", true, true,
                    (t, v) -> t.updateObject("showIcon", v)),
            new Site("position", "Invalid position value: ", NOTE_SUFFIX, true, false,
                    (t, v) -> t.addNote(v)),
            new Site("anchorEdge", "Invalid anchorEdge value: ", "", true, false,
                    (t, v) -> t.anchor(v)));

    private static Site styling(String key, boolean blankIsAbsent) {
        return new Site(key, "Invalid " + key + " value: ", "", blankIsAbsent, true,
                (t, v) -> t.updateObject(key, v));
    }

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-caller-value-echo");
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        f.object(f.view, "vo-b", b, 400, 40, 120, 60);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- an ordinary value reads exactly as before, on every site ------------------------------

    @Test
    public void shouldRepeatAnOrdinaryFigureTypeAsBefore() {
        assertEquals("Invalid figureType value: 'hexagon'", updateObject("figureType", "hexagon"));
    }

    @Test
    public void shouldRepeatAnOrdinaryTextAlignmentAsBefore() {
        assertEquals("Invalid textAlignment value: 'justify'", updateObject("textAlignment", "justify"));
    }

    @Test
    public void shouldRepeatAnOrdinaryVerticalTextAlignmentAsBefore() {
        assertEquals("Invalid verticalTextAlignment value: 'baseline'",
                updateObject("verticalTextAlignment", "baseline"));
    }

    @Test
    public void shouldRepeatAnOrdinaryFontStyleAsBefore() {
        assertEquals("Invalid fontStyle value: 'bold-ish'", updateObject("fontStyle", "bold-ish"));
    }

    @Test
    public void shouldRepeatAnOrdinaryLineStyleAsBefore() {
        assertEquals("Invalid lineStyle value: 'wavy'", updateObject("lineStyle", "wavy"));
    }

    @Test
    public void shouldRepeatAnOrdinaryGradientAsBefore() {
        assertEquals("Invalid gradient value: 'diagonal'", updateObject("gradient", "diagonal"));
    }

    @Test
    public void shouldRepeatAnOrdinaryBorderTypeAsBefore() {
        assertEquals("Invalid borderType value: 'double'", updateObject("borderType", "double"));
    }

    @Test
    public void shouldRepeatAnOrdinaryColourAsBefore() {
        assertEquals("Invalid fillColor format: 'red'", updateObject("fillColor", "red"));
    }

    @Test
    public void shouldRepeatAnOrdinaryImagePositionAsBefore() {
        assertEquals("Invalid image position: 'centre'", updateObject("imagePosition", "centre"));
    }

    @Test
    public void shouldRepeatAnOrdinaryShowIconAsBefore() {
        assertEquals("Invalid showIcon value: 'sometimes'", updateObject("showIcon", "sometimes"));
    }

    @Test
    public void shouldRepeatAnOrdinaryNotePositionAsBefore() {
        assertEquals("Invalid position value: 'middle'" + NOTE_SUFFIX, addNote("middle"));
    }

    @Test
    public void shouldRepeatAnOrdinaryAnchorEdgeAsBefore() {
        assertEquals("Invalid anchorEdge value: 'diagonal'", anchor("diagonal"));
    }

    // ---- the bound -----------------------------------------------------------------------------

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inAStylingValue() {
        String msg = updateObject("fontStyle", "a".repeat(39) + EMOJI + "zz");

        assertEquals("Invalid fontStyle value: '" + "a".repeat(39) + "' (the start of 43 characters)", msg);
    }

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inAnAnchorEdge() {
        String msg = anchor("a".repeat(39) + EMOJI + "zz");

        assertEquals("Invalid anchorEdge value: '" + "a".repeat(39) + "' (the start of 43 characters)", msg);
    }

    @Test
    public void shouldRepeatOnlyTheStartOfALongValue_andSaySo() {
        String msg = updateObject("fillColor", "q".repeat(500));

        assertEquals("Invalid fillColor format: '" + "q".repeat(40) + "' (the start of 500 characters)", msg);
    }

    @Test
    public void shouldRepeatAValueOfExactlyFortyUnitsWhole() {
        String msg = updateObject("gradient", "g".repeat(40));

        assertEquals("Invalid gradient value: '" + "g".repeat(40) + "'", msg);
    }

    // ---- the escapes ---------------------------------------------------------------------------

    @Test
    public void shouldEscapeControlsQuotesAndBackslashes() {
        String msg = updateObject("showIcon", "a\nb\tc\u0000d'e\\f\rg");

        assertEquals("Invalid showIcon value: 'a\\nb\\tc\\u0000d\\'e\\\\f\\rg'", msg);
    }

    @Test
    public void shouldEscapeInvisibleFormatCharacters() {
        String msg = updateObject("lineStyle", "a\u200Bb\uFEFFc\u00ADd\u2060e\u206Af");

        assertEquals("Invalid lineStyle value: 'a\\u200Bb\\uFEFFc\\u00ADd\\u2060e\\u206Af'", msg);
    }

    @Test
    public void shouldKeepTheJoinersThatTextAndEmojiNeed() {
        assertEquals("Invalid lineStyle value: 'a\u200Cb\u200Dc'", updateObject("lineStyle", "a\u200Cb\u200Dc"));
    }

    @Test
    public void shouldEscapeALoneSurrogateTheCallerSent() {
        assertEquals("Invalid position value: '\\uD83D'" + NOTE_SUFFIX, addNote("\uD83D"));
    }

    @Test
    public void shouldEscapeANulInAnAnchorEdge() {
        assertEquals("Invalid anchorEdge value: 'x\\u0000y'", anchor("x\u0000y"));
    }

    // ---- bulk ----------------------------------------------------------------------------------

    @Test
    public void shouldEscapeAStylingValue_inBulk() {
        assertEquals("Invalid lineStyle value: 'a\\nb'", bulkRow("lineStyle", "a\nb"));
    }

    @Test
    public void shouldBoundAColour_inBulk() {
        assertEquals("Invalid lineColor format: '" + "a".repeat(39) + "' (the start of 43 characters)",
                bulkRow("lineColor", "a".repeat(39) + EMOJI + "zz"));
    }

    @Test
    public void shouldEscapeAnImageValue_inBulk() {
        assertEquals("Invalid showIcon value: 'x\\ny'", bulkRow("showIcon", "x\ny"));
    }

    // ---- the helper ----------------------------------------------------------------------------

    @Test
    public void shouldQuoteANullAsBefore() {
        assertEquals("'null'", CallerText.quoted(null));
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
                String why = whyBroken(site, value, msg);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
        }
        long blank = NASTY.stream().filter(String::isBlank).count();
        long blankSkipping = SITES.stream().filter(Site::blankIsAbsent).count();
        assertEquals(NASTY.size() * SITES.size() - blank * blankSkipping, checked);
        assertTrue(broken.size() + " malformed refusals:\n" + String.join("\n", broken), broken.isEmpty());
    }

    @Test
    public void everyBulkRefusal_shouldRepeatTheCallersStart_wellFormed() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            for (Site site : SITES) {
                if (!site.inBulk()) {
                    continue;
                }
                if (site.blankIsAbsent() && value.isBlank()) {
                    continue; // bulk reads it as absent too
                }
                String msg = bulkRow(site.name(), value);
                checked++;
                String why = whyBroken(site, value, msg);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
        }
        long blank = NASTY.stream().filter(String::isBlank).count();
        long inBulk = SITES.stream().filter(Site::inBulk).count();
        long inBulkBlankSkipping = SITES.stream().filter(s -> s.inBulk() && s.blankIsAbsent()).count();
        assertEquals(10, inBulk);
        assertEquals(NASTY.size() * inBulk - blank * inBulkBlankSkipping, checked);
        assertTrue(broken.size() + " malformed refusals:\n" + String.join("\n", broken), broken.isEmpty());
    }

    // ---- harness -------------------------------------------------------------------------------

    /**
     * Null when the refusal keeps the site's own text, holds nothing malformed, marks a cut value as partial,
     * and repeats exactly what it should: read back through its escapes, the quoted text is the whole value
     * when it is at most the bound, and otherwise its first {@link CallerText#MAX_ECHOED} units, one fewer
     * when the last of those starts a surrogate pair.
     */
    private static String whyBroken(Site site, String value, String msg) {
        String why = whyMalformed(msg);
        if (why != null) {
            return why;
        }
        boolean cut = value.length() > CallerText.MAX_ECHOED;
        String marker = cut ? " (the start of " + value.length() + " characters)" : "";
        String close = "'" + marker + site.suffix();
        if (!msg.startsWith(site.prefix() + "'") || !msg.endsWith(close)
                || msg.length() < site.prefix().length() + 1 + close.length()) {
            return cut ? "surrounding text changed, or a cut value not marked as partial" : "surrounding text changed";
        }
        String echoed = unescape(msg.substring(site.prefix().length() + 1, msg.length() - close.length()));
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

    private String updateObject(String key, String value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewObjectId", "vo-a");
        args.put(key, value);
        return message(f.serve("update-view-object", args));
    }

    private String anchor(String edge) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewObjectId", "vo-a");
        args.put("anchorTarget", "vo-b");
        args.put("anchorEdge", edge);
        return message(f.serve("update-view-object", args));
    }

    private String addNote(String position) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", f.view.getId());
        args.put("content", "note");
        args.put("position", position);
        return message(f.serve("add-note-to-view", args));
    }

    /** The decoded {@code error.message} of a refused handler call, after checking it is a caller error. */
    private static String message(String out) {
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        try {
            JsonNode error = JSON.readTree(out.substring("ERROR ".length())).path("error");
            assertEquals(out, ErrorCode.INVALID_PARAMETER.name(), error.path("code").asText());
            return error.path("message").asText();
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + out, e);
        }
    }

    private String bulkRow(String key, String value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewObjectId", "vo-a");
        params.put(key, value);
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION,
                    List.of(new BulkOperation("update-view-object", params)), null, false);
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
