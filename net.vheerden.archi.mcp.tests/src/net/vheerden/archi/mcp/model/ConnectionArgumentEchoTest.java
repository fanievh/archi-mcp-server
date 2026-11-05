package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Pins how the connection tools' refusals repeat a value or key the caller sent: bounded to its first
 * 40 UTF-16 units without cutting a character in two, and escaped so no control character, unpaired
 * surrogate, line separator, direction control, quote or backslash reaches the message raw. The message is read after decoding the JSON
 * response, so an escape asserted here is text in the message, not the transport's own escaping.
 *
 * <p>Before, a value whose 40th unit began an emoji left half of it in the message (written to the
 * HTTP response as bytes that are not UTF-8), a newline split the DEBUG log entry in two, and a
 * number was named by a class name lower-cased in the default locale.</p>
 */
public class ConnectionArgumentEchoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String EMOJI = "\uD83D\uDE00";

    /** Man, zero-width joiner, woman: one emoji sequence whose joiner must not be escaped. */
    private static final String JOINED = "\uD83D\uDC68\u200D\uD83D\uDC69";

    /** Values that each break a naive echo, alone or past the bound. */
    static final List<String> NASTY = List.of(
            "a\nb", "\r\n", "\t", "\u0000", "\u001B[31m", "\u007F", "\u0085", "\u009F",
            "\uD83D", "\uDE00", "x\uDE00\uD83D", "\"", "\\", "\\n", "\"\\\"",
            "a".repeat(39) + EMOJI + "zz", "a".repeat(38) + EMOJI + "zz", "a".repeat(39) + "\uD83D",
            "a".repeat(39) + "\uD83Dz", "a".repeat(40) + "\n", "\n".repeat(60), EMOJI.repeat(30),
            "\uD83D".repeat(45), ("\"\\" + EMOJI + "\n").repeat(20),
            "a\u2028b", "\u2029", "\u202Eevil", "x\u2066y\u2069", "\u200E\u200F", "\u061C", "\u202A\u202B\u202C\u202D",
            "a".repeat(39) + "\u2028" + "z", JOINED.repeat(10),
            "a\u200Bb", "\uFEFF", "\u00AD", "\u2060\u206A\u206F", "a".repeat(39) + "\u200B" + "z");

    private ViewContentsFixture f;
    private IArchimateRelationship relBa;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-connection-argument-echo");
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        IDiagramModelArchimateObject va = f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        IDiagramModelArchimateObject vb = f.object(f.view, "vo-b", b, 400, 40, 120, 60);
        f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), va, vb);
        relBa = f.relationship("rel-ba", b, a);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- the bound -----------------------------------------------------------------------------

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_ratherThanSplitIt() {
        String value = "a".repeat(39) + EMOJI + "zz";

        String msg = updateMessage("lineWidth", value);

        assertTrue(msg, msg.contains("got a string of 43 characters starting \"" + "a".repeat(39) + "\")"));
        assertWellFormed(msg);
    }

    @Test
    public void shouldDropAnEmojiThatStartsAtTheLastEchoedUnit_inBulk() {
        String msg = bulkRow("update-view-connection", updateParams("labelPosition", "a".repeat(39) + EMOJI + "zz"));

        assertTrue(msg, msg.contains("a string of 43 characters starting \"" + "a".repeat(39) + "\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldEchoAnEmojiWhole_whenItEndsAtTheLastEchoedUnit() {
        String msg = updateMessage("labelPosition", "a".repeat(38) + EMOJI + "zz");

        assertTrue(msg, msg.contains("a string of 42 characters starting \"" + "a".repeat(38) + EMOJI + "\". "));
    }

    @Test
    public void shouldDropALoneHighSurrogateAtTheLastEchoedUnit() {
        String msg = updateMessage("labelPosition", "a".repeat(39) + "\uD83Dz");

        assertTrue(msg, msg.contains("a string of 41 characters starting \"" + "a".repeat(39) + "\". "));
    }

    @Test
    public void shouldCutAnAsciiValueAtFortyUnits() {
        String msg = updateMessage("labelPosition", "b".repeat(41));

        assertTrue(msg, msg.contains("a string of 41 characters starting \"" + "b".repeat(40) + "\". "));
    }

    // ---- the escapes ---------------------------------------------------------------------------

    @Test
    public void shouldEscapeControlsQuotesAndBackslashes_inAShortValue() {
        String msg = updateMessage("labelPosition", "a\nb\tc\u0000d\"e\\f\rg\u001Bh");

        assertTrue(msg, msg.contains("got the string \"a\\nb\\tc\\u0000d\\\"e\\\\f\\rg\\u001Bh\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldEscapeControlsQuotesAndBackslashes_inAShortValue_inBulk() {
        String msg = bulkRow("add-connection-to-view", addParams("showLabel", "a\nb\tc\u0000d\"e\\f"));

        assertTrue(msg, msg.startsWith("showLabel must be true or false, got the string \"a\\nb\\tc\\u0000d\\\"e\\\\f\". "));
    }

    @Test
    public void shouldEscapeANewlineInsideTheEchoedStartOfALongValue() {
        String msg = updateMessage("labelPosition", "a\nb" + "c".repeat(50));

        assertTrue(msg, msg.contains("a string of 53 characters starting \"a\\nb" + "c".repeat(37) + "\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldEscapeLineSeparatorsAndDirectionControls() {
        String msg = updateMessage("labelPosition", "a\u2028b\u2029c\u202Ed\u2066e\u200Ff\u061Cg");

        assertTrue(msg, msg.contains("got the string \"a\\u2028b\\u2029c\\u202Ed\\u2066e\\u200Ff\\u061Cg\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldEscapeInvisibleFormatCharacters_butKeepTheJoiners() {
        String msg = updateMessage("labelPosition", "a\u200Bb\uFEFFc\u00ADd\u206Fe\u200Cf");

        assertTrue(msg, msg.contains("got the string \"a\\u200Bb\\uFEFFc\\u00ADd\\u206Fe\u200Cf\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldKeepAJoinedEmojiSequenceWhole() {
        String msg = updateMessage("labelPosition", JOINED);

        assertTrue(msg, msg.contains("got the string \"" + JOINED + "\". "));
    }

    @Test
    public void shouldEscapeALoneSurrogateTheCallerSent() {
        String msg = message(f.serve("add-connection-to-view", addParams("showLabel", "\uD83D")));

        assertTrue(msg, msg.contains("got the string \"\\uD83D\". "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldReadACallersBackslashNDifferentlyFromANewline() {
        String newline = updateMessage("labelPosition", "a\nb");
        String backslashN = updateMessage("labelPosition", "a\\nb");

        assertTrue(newline, newline.contains("the string \"a\\nb\""));
        assertTrue(backslashN, backslashN.contains("the string \"a\\\\nb\""));
    }

    // ---- the type name -------------------------------------------------------------------------

    @Test
    public void shouldNameANumberTheSameWay_underATurkishDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            String showLabel = message(f.serve("add-connection-to-view", addParams("showLabel", 1)));
            String labelPosition = updateMessage("labelPosition", 1.5);
            String bulk = bulkRow("update-view-connection", updateParams("showLabel", 1));

            assertTrue(showLabel, showLabel.contains("got the integer 1. "));
            assertTrue(labelPosition, labelPosition.contains("got the double 1.5. "));
            assertTrue(bulk, bulk.contains("got the integer 1. "));
        } finally {
            Locale.setDefault(before);
        }
    }

    // ---- unknown keys --------------------------------------------------------------------------

    @Test
    public void shouldRepeatAnOrdinaryUnknownKeyAsBefore() {
        String msg = updateMessage("lineStyl", "solid");

        assertTrue(msg, msg.startsWith("update-view-connection does not accept 'lineStyl'. "));
    }

    @Test
    public void shouldEscapeAnUnknownKeyHoldingANewline() {
        String msg = updateMessage("bad\nkey", 1);

        assertTrue(msg, msg.startsWith("update-view-connection does not accept 'bad\\nkey'. "));
        assertWellFormed(msg);
    }

    @Test
    public void shouldEscapeTheQuoteAroundAnUnknownKey() {
        String msg = updateMessage("it's", 1);

        assertTrue(msg, msg.startsWith("update-view-connection does not accept 'it\\'s'. "));
    }

    @Test
    public void shouldRepeatOnlyTheStartOfALongUnknownKey() {
        String key = "k".repeat(500);

        String msg = updateMessage(key, 1);

        assertTrue(msg, msg.startsWith("update-view-connection does not accept a key of 500 characters starting '"
                + "k".repeat(40) + "'. "));
        assertFalse("the whole key is not echoed back", msg.contains("k".repeat(41)));
    }

    @Test
    public void shouldBoundAndEscapeALongUnknownKey_inBulk() {
        String msg = bulkRow("update-view-connection", updateParams("x".repeat(39) + EMOJI + "\n", 1));

        assertTrue(msg, msg.startsWith("update-view-connection does not accept a key of 42 characters starting '"
                + "x".repeat(39) + "'. "));
        assertWellFormed(msg);
    }

    // ---- the invariants, over every reader -----------------------------------------------------

    @Test
    public void everyRefusal_shouldHoldNoUnpairedSurrogateAndNoControlCharacter() {
        List<Function<String, String>> readers = List.of(
                v -> updateMessage("labelPosition", v),
                v -> updateMessage("showLabel", v),
                v -> updateMessage("lineWidth", v),
                v -> updateMessage(v, 1),
                v -> message(f.serve("add-connection-to-view", addParams("showLabel", v))),
                v -> message(f.serve("add-connection-to-view", addParams("labelPosition", v))),
                v -> message(f.serve("auto-connect-view", Map.of("viewId", f.view.getId(), "showLabel", v))),
                v -> bulkRow("update-view-connection", updateParams("labelPosition", v)),
                v -> bulkRow("update-view-connection", updateParams("lineWidth", v)),
                v -> bulkRow("update-view-connection", updateParams(v, 1)),
                v -> bulkRow("add-connection-to-view", addParams("showLabel", v)));
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            for (int r = 0; r < readers.size(); r++) {
                String msg = readers.get(r).apply(value);
                checked++;
                String why = whyMalformed(msg);
                if (why != null) {
                    broken.add("reader " + r + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
        }
        assertEquals(NASTY.size() * readers.size(), checked);
        assertTrue(broken.size() + " malformed refusals:\n" + String.join("\n", broken), broken.isEmpty());
    }

    // ---- harness -------------------------------------------------------------------------------

    private static void assertWellFormed(String msg) {
        String why = whyMalformed(msg);
        assertTrue(why + ": " + visible(msg), why == null);
    }

    /**
     * Null when the text holds no unpaired surrogate, ISO control, line or paragraph separator, or format
     * character other than the zero-width joiner and non-joiner.
     */
    static String whyMalformed(String msg) {
        int[] cps = msg.codePoints().toArray();
        for (int cp : cps) {
            // A well-formed pair arrives as one supplementary code point; a surrogate here is unpaired.
            if (Character.isSurrogate((char) cp) && cp <= Character.MAX_VALUE) {
                return String.format("unpaired surrogate U+%04X", cp);
            }
            if (Character.isISOControl(cp)) {
                return String.format("control character U+%04X", cp);
            }
            if (cp == 0x2028 || cp == 0x2029) {
                return String.format("line or paragraph separator U+%04X", cp);
            }
            // Every other format character is invisible; only the joiners that text and emoji need may stay.
            if (cp <= Character.MAX_VALUE && Character.getType(cp) == Character.FORMAT && cp != 0x200C && cp != 0x200D) {
                return String.format("format character U+%04X", cp);
            }
        }
        return null;
    }

    static String visible(String s) {
        StringBuilder out = new StringBuilder();
        s.chars().forEach(c -> out.append(c < 0x20 || (c >= 0x7F && c <= 0x9F) || (c >= 0x2000 && c <= 0x206F)
                || c == 0x061C || Character.isSurrogate((char) c)
                ? String.format("<U+%04X>", c) : String.valueOf((char) c)));
        return out.toString();
    }

    private String updateMessage(String key, Object value) {
        return message(f.serve("update-view-connection", updateParams(key, value)));
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

    private String bulkRow(String tool, Map<String, Object> params) {
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION,
                    List.of(new BulkOperation(tool, params)), null, false);
            fail("expected a refusal, got allSucceeded=" + result.allSucceeded());
            return null;
        } catch (BulkValidationException e) {
            List<BulkOperationFailure> rows = e.getFailures();
            assertEquals("one failure row: " + rows, 1, rows.size());
            assertEquals(ErrorCode.INVALID_PARAMETER.name(), rows.get(0).errorCode());
            return rows.get(0).message();
        }
    }

    private static Map<String, Object> updateParams(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewConnectionId", "conn-ab");
        p.put(key, value);
        return p;
    }

    private Map<String, Object> addParams(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewId", f.view.getId());
        p.put("relationshipId", relBa.getId());
        p.put("sourceViewObjectId", "vo-b");
        p.put("targetViewObjectId", "vo-a");
        p.put(key, value);
        return p;
    }
}
