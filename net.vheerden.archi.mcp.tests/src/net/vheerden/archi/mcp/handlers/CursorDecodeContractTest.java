package net.vheerden.archi.mcp.handlers;

import static net.vheerden.archi.mcp.JavaSourceText.blank;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.PaginationCursor;

/**
 * Keeps {@code CursorModelCheck.decode} the only production code that reads a pagination cursor.
 *
 * <p>A cursor names the model instance it pages and the version it was cut at. Reading one and
 * honouring it without both checks hands the agent another model's page, or a stale one, presented
 * as the continuation of its results. {@code CursorModelCheck.decode} reads the cursor and runs both
 * checks as one step. {@code PaginationCursor.decode} is public (it lives in another package), so a
 * new paged tool could call it directly and skip the checks; this test refuses that.</p>
 *
 * <p>The property is checked over every Java file under {@code net.vheerden.archi.mcp/src}, not over a
 * list of the paged tools, so a new tool is covered without editing this test. Comments and string
 * and character literals (text blocks included) are blanked before matching, so a mention in prose
 * never counts as a call. Caught spellings: {@code PaginationCursor.decode(}, qualified or not and
 * with any whitespace around the dot; the method reference {@code PaginationCursor::decode}; and a
 * bare {@code decode(} in a file that statically imports {@code PaginationCursor.decode} or
 * {@code PaginationCursor.*}. <strong>Not caught:</strong> reflection, a call through a
 * variable or a type alias that this text match cannot resolve, and a call spelled with Unicode
 * escapes (a backslash-u escape written for the dot, say), which the scan does not translate.</p>
 */
public class CursorDecodeContractTest {

    private static final String SRC = "net.vheerden.archi.mcp/src";

    /** The helper itself, and the codec (which may call its own method). */
    private static final Set<String> ALLOWED = Set.of(
            "net/vheerden/archi/mcp/handlers/CursorModelCheck.java",
            "net/vheerden/archi/mcp/response/PaginationCursor.java");

    private static final Pattern QUALIFIED_CALL =
            Pattern.compile("\\bPaginationCursor\\s*(?:\\.\\s*decode\\s*\\(|::\\s*decode\\b)");
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "\\bimport\\s+static\\s+[\\w.\\s]*\\bPaginationCursor\\s*\\.\\s*(?:decode|\\*)\\s*;");
    private static final Pattern BARE_CALL = Pattern.compile("(?<![\\w.$:])decode\\s*\\(");

    @Test
    public void shouldDecodeOnlyThroughCursorModelCheck_whenProductionCodeReadsACursor() {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources()) {
            String rel = relative(file);
            if (!ALLOWED.contains(rel) && decodesACursor(code(file))) {
                offenders.add(rel);
            }
        }
        assertTrue("These files read a pagination cursor directly, so nothing checks that it belongs to the "
                + "call's model and version: " + offenders + ". Call CursorModelCheck.decode, which checks the "
                + "cursor's model and version.", offenders.isEmpty());
    }

    @Test
    public void shouldDecodeInsideCursorModelCheck_whenThePagedToolsReadACursor() {
        // Without this, renaming or moving the helper would leave the property above true of nothing.
        Path helper = locate(SRC).resolve("net/vheerden/archi/mcp/handlers/CursorModelCheck.java");
        assertTrue("CursorModelCheck must be the code that reads a cursor",
                QUALIFIED_CALL.matcher(code(helper)).find());
    }

    @Test
    public void shouldIgnoreComments_andCatchEveryCallSpelling_whenScanningSource() {
        assertTrue(decodesACursor(blank("x = PaginationCursor.decode(c);")));
        assertTrue(decodesACursor(blank("x = PaginationCursor . decode (c);")));
        assertTrue(decodesACursor(blank("x = net.vheerden.archi.mcp.response.PaginationCursor.decode(c);")));
        assertTrue(decodesACursor(blank("f = PaginationCursor::decode;")));
        assertTrue(decodesACursor(blank(
                "import static net.vheerden.archi.mcp.response.PaginationCursor.decode;\nx = decode(c);")));
        assertTrue(decodesACursor(blank(
                "import static net.vheerden.archi.mcp.response.PaginationCursor.*;\nx = decode(c);")));

        assertTrue("a comment is not a call", !decodesACursor(blank("// PaginationCursor.decode(c)\n")));
        assertTrue("a block comment is not a call",
                !decodesACursor(blank("/** see {@link PaginationCursor#decode} and PaginationCursor.decode( */")));
        assertTrue("a string is not a call", !decodesACursor(blank("s = \"PaginationCursor.decode(c)\";")));
        assertTrue("a text block is not a call",
                !decodesACursor(blank("s = \"\"\"\n PaginationCursor.decode(c)\n\"\"\";")));
        assertTrue("another class's decode is not a cursor read", !decodesACursor(blank("x = Base64.decode(c);")));
        assertTrue("a bare decode( without the static import is someone else's",
                !decodesACursor(blank("x = decode(c);")));
    }

    @Test
    public void shouldKeepATextBlockOpen_whenItContainsAnEscapedTripleQuote() {
        // An escaped \""" does not close the block: the mention inside it is prose, the call after it is code
        assertTrue("a mention inside the block is not a call",
                !decodesACursor(blank("s = \"\"\"\n say \\\"\"\" PaginationCursor.decode(c)\n\"\"\";\n")));
        assertTrue("a call after the block is still found",
                decodesACursor(blank("s = \"\"\"\n say \\\"\"\"hi\\\"\"\"\n\"\"\";\nx = PaginationCursor.decode(c);")));
    }

    @Test
    public void shouldEndALiteralAtTheLineBreak_whenABackslashPrecedesIt() {
        // Not valid Java, but the scan must not let a broken literal swallow the next line's code
        assertTrue(decodesACursor(blank("s = \"abc\\\nx = PaginationCursor.decode(c);")));
        assertTrue(decodesACursor(blank("ch = '\\\nx = PaginationCursor.decode(c);")));
    }

    @Test
    public void shouldRefuseADecodedResult_whenNotExactlyOneSideIsSet() throws Exception {
        PaginationCursor.CursorData cursor = PaginationCursor.decode(
                PaginationCursor.encode("1", null, 1, 1, 2, Map.of()));
        ErrorResponse refusal = new ErrorResponse(ErrorCode.INVALID_CURSOR, "refused");
        new CursorModelCheck.Decoded(cursor, null);
        new CursorModelCheck.Decoded(null, refusal);
        assertThrows(IllegalArgumentException.class, () -> new CursorModelCheck.Decoded(null, null));
        assertThrows(IllegalArgumentException.class, () -> new CursorModelCheck.Decoded(cursor, refusal));
    }

    // ---- the match ----------------------------------------------------------------------------

    private static boolean decodesACursor(String code) {
        return QUALIFIED_CALL.matcher(code).find()
                || (STATIC_IMPORT.matcher(code).find() && BARE_CALL.matcher(code).find());
    }

    // ---- the tree -----------------------------------------------------------------------------

    private static List<Path> productionSources() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(locate(SRC))) {
            walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + SRC, e);
        }
        assertTrue("the scan must actually find the production tree, or it certifies nothing: "
                + files.size(), files.size() > 50);
        return files;
    }

    private static String relative(Path file) {
        return locate(SRC).relativize(file).toString().replace('\\', '/');
    }

    private static String code(Path file) {
        try {
            return blank(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static Path locate(String relative) {
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
}
