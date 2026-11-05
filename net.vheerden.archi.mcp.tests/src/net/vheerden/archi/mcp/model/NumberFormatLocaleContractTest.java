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
 * No {@code String.format} in the plugin's source takes its digits or its decimal separator from the
 * JVM default.
 *
 * <p>{@code String.format(fmt, args)} with no {@code Locale} formats in
 * {@code Locale.getDefault(Locale.Category.FORMAT)}. On a German, French or South African default,
 * {@code %.1f} of 1.5 reads {@code 1,5}; on Arabic, Persian, Bengali and similar defaults
 * {@code %d} and {@code %f} write that script's own digits. Archi takes the desktop's locale, and
 * every number here is read by an MCP client or a maintainer grepping a log, so each call passes
 * {@code Locale.ROOT} as its first argument; any other locale, {@code Locale.getDefault()} included,
 * is reported. {@code String.formatted} has no {@code Locale} overload, and a {@code String::format}
 * method reference cannot be checked here, so neither is used at all.</p>
 *
 * <p>The scan reads code only (see {@link CaseFoldLocaleContractTest#codeOnly}) and reads the first
 * argument past any whitespace and line breaks, because most calls put the format string on the
 * line after {@code String.format(}. Every offender is reported in one failure, not just the
 * first.</p>
 */
public class NumberFormatLocaleContractTest {

    private static final String SHIPPED_ROOT = "net.vheerden.archi.mcp/src";

    /** Fewer files than this means the walk found the wrong tree, not a clean one. */
    private static final int MIN_FILES_SCANNED = 100;

    private static final Pattern FORMAT_CALL = Pattern.compile("\\bString\\s*\\.\\s*format\\s*\\(");

    /** A first argument of {@code Locale.ROOT}, whether imported or fully qualified. */
    private static final Pattern ROOT_FIRST = Pattern.compile(
            "\\s*(java\\s*\\.\\s*util\\s*\\.\\s*)?Locale\\s*\\.\\s*ROOT\\b");

    /** A {@code formatted} call, or a {@code String::format} method reference. */
    private static final Pattern ALWAYS_OFFENDS = Pattern.compile(
            "\\.\\s*formatted\\s*\\(|\\bString\\s*::\\s*format\\b");

    @Test
    public void shouldNameTheLocale_whenSourceFormatsANumber() throws IOException {
        Path root = locateRepoFile(SHIPPED_ROOT);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue("scanned only " + files.size() + " .java files under " + root
                + "; the walk found the wrong tree", files.size() > MIN_FILES_SCANNED);

        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            for (int line : unlocalisedFormatLines(Files.readString(file, StandardCharsets.UTF_8))) {
                offenders.add(root.relativize(file) + ":" + line);
            }
        }
        assertTrue(offenders.size() + " String.format calls do not pass Locale.ROOT first (or use formatted or a "
                + "String::format reference, which cannot); pass Locale.ROOT as the first argument:\n  "
                + String.join("\n  ", offenders), offenders.isEmpty());
    }

    @Test
    public void shouldCountOnlyCode_andReadTheFirstArgumentPastLineBreaks() {
        String source = String.join("\n",
                "String a = String.format(\"%d\", 1);",
                "String b = String.format(",
                "        \"%.1f\", 1.5);",
                "String c = String.format(",
                "        Locale.ROOT, \"%.1f\", 1.5);",
                "String d = String.format(java.util.Locale.ROOT, \"%d\", 2);",
                "// String e = String.format(\"%d\", 1);",
                "String f = \"String.format(\"; /* String.format(\"%d\", 1) */",
                "String g = \"%d items\".formatted(3);",
                "String h = String",
                "        .format(\"%x\", 4);",
                "String k = MessageFormat.format(\"{0}\", 5); String m = s.formattedName();",
                "String n = String.format(Locale.getDefault(), \"%.1f\", 1.5);",
                "String q = String.format(java.util.Locale.ROOTS, \"%d\", 6);",
                "BiFunction<String, Object[], String> r = String::format;",
                "String t = String.format(Locale . ROOT, \"%d\", 7);");

        assertEquals(List.of(1, 2, 9, 10, 13, 14, 15), unlocalisedFormatLines(source));
    }

    /**
     * The 1-based lines on which a {@code String.format} call without a leading {@code Locale.ROOT}
     * argument, a {@code formatted} call or a {@code String::format} reference starts.
     */
    static List<Integer> unlocalisedFormatLines(String source) {
        String code = CaseFoldLocaleContractTest.codeOnly(source);
        List<Integer> starts = new ArrayList<>();
        Matcher call = FORMAT_CALL.matcher(code);
        while (call.find()) {
            if (!ROOT_FIRST.matcher(code).region(call.end(), code.length()).lookingAt()) {
                starts.add(call.start());
            }
        }
        Matcher formatted = ALWAYS_OFFENDS.matcher(code);
        while (formatted.find()) {
            starts.add(formatted.start());
        }
        List<Integer> lines = new ArrayList<>();
        for (int start : starts.stream().sorted().toList()) {
            int line = 1;
            for (int i = 0; i < start; i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
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
