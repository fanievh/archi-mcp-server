package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * No case fold in the plugin's source takes its locale from the JVM default without saying so.
 *
 * <p>{@code String.toLowerCase()} and {@code toUpperCase()} with no argument fold in
 * {@code Locale.getDefault()}. Under a Turkish or Azeri default, {@code "I"} lower-cases to a dotless
 * {@code ı} and {@code "i"} upper-cases to a dotted {@code İ}, so a keyword such as {@code "Italic"}
 * or {@code "right"} no longer matches its own spelling. Archi takes the desktop's locale, so a call
 * that works on one machine is refused on another. Every fold names its locale instead:
 * {@code Locale.ROOT} for a keyword or an internal code, {@code Locale.getDefault()} where matching
 * the user's own text in the user's own locale is the intent.</p>
 *
 * <p>The scan reads code only: comments and string, character and text-block literals are blanked
 * first, so a Javadoc that explains the trap by quoting {@code "TIFF".toLowerCase()} does not count.
 * Every offender is reported in one failure, not just the first.</p>
 */
public class CaseFoldLocaleContractTest {

    private static final String SHIPPED_ROOT = "net.vheerden.archi.mcp/src";

    /** Fewer files than this means the walk found the wrong tree, not a clean one. */
    private static final int MIN_FILES_SCANNED = 100;

    /**
     * A no-argument call, with or without a break after the dot, or a method reference, which always
     * resolves to the no-argument overload when used as a {@code Function<String, String>}.
     */
    private static final Pattern NO_ARGUMENT_FOLD = Pattern.compile(
            "\\.\\s*to(Lower|Upper)Case\\s*\\(\\s*\\)|::\\s*to(Lower|Upper)Case\\b");

    @Test
    public void shouldNameTheLocale_whenSourceFoldsCase() throws IOException {
        Path root = locateRepoFile(SHIPPED_ROOT);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue("scanned only " + files.size() + " .java files under " + root
                + "; the walk found the wrong tree", files.size() > MIN_FILES_SCANNED);

        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            for (int line : noArgumentFoldLines(Files.readString(file, StandardCharsets.UTF_8))) {
                offenders.add(root.relativize(file) + ":" + line);
            }
        }
        assertTrue(offenders.size() + " case folds take the JVM default locale without naming it; pass "
                + "Locale.ROOT for a keyword or code, Locale.getDefault() for the user's own text:\n  "
                + String.join("\n  ", offenders), offenders.isEmpty());
    }

    @Test
    public void shouldCountOnlyCode_notCommentsOrLiterals() {
        String source = String.join("\n",
                "/** Under tr, {@code \"TIFF\".toLowerCase()} is dotless. */",
                "// s.toUpperCase() in a line comment",
                "/* x.toLowerCase() in a block",
                "   comment */ String a = \"q.toLowerCase()\";",
                "char c = '\"'; String b = v.toLowerCase();",
                "String t = \"\"\"",
                "    y.toUpperCase()",
                "    \"\"\"; String d = w.toUpperCase(",
                "    );",
                "String e = \"\\\\\"; String g = z.toLowerCase(java.util.Locale.ROOT);",
                "names.stream().map(String::toUpperCase);",
                "String h = q.",
                "        toLowerCase();",
                "String k = r.toLowerCaseFirst(); String m = s.toUpperCase(Locale.ROOT);");

        assertEquals(List.of(5, 8, 11, 12), noArgumentFoldLines(source));
    }

    /** The 1-based lines on which a no-argument {@code toLowerCase()} or {@code toUpperCase()} call starts. */
    static List<Integer> noArgumentFoldLines(String source) {
        String code = codeOnly(source);
        List<Integer> lines = new ArrayList<>();
        Matcher m = NO_ARGUMENT_FOLD.matcher(code);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * The source with every comment and literal replaced by spaces, line breaks kept, so offsets and
     * line numbers still point at the original text.
     */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(source, i, end, out);
                i = end;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = closing(source, i + 3, "\"\"\"");
                blank(source, i, end, out);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = closing(source, i + 1, String.valueOf(c));
                blank(source, i, end, out);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The offset just past the first unescaped {@code delimiter} at or after {@code from}. */
    private static int closing(String source, int from, String delimiter) {
        int i = from;
        while (i < source.length()) {
            if (source.charAt(i) == '\\') {
                i += 2;
            } else if (source.startsWith(delimiter, i)) {
                return i + delimiter.length();
            } else {
                i++;
            }
        }
        return source.length();
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
    }

    private static Path locateRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory inside the checkout."));
    }
}
