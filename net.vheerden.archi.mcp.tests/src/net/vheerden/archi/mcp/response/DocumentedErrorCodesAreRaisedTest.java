package net.vheerden.archi.mcp.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.JavaSourceText;
import net.vheerden.archi.mcp.RepoFiles;

/**
 * The error codes the docs list are codes the server has, and every code the server has is raised
 * somewhere.
 *
 * <p>Documented means the codes in the tables of {@code docs/mcp-integration.md} § Error Code
 * Categories and {@code docs/mutation-model.md} § Common Error Codes. Raised means an
 * {@code ErrorCode.X} reference in the plugin's code (comments and strings blanked) outside
 * {@code ErrorCode.java}, not counting a comparison: {@code == ErrorCode.X}, {@code != ErrorCode.X}
 * and {@code equals(ErrorCode.X)} read a code, they do not return one. Three things are asserted: a
 * documented code is in the enum (a typo in a table), a documented code is raised, and an enum
 * constant is raised (a code no call can return is a dead promise). A code kept on purpose with no
 * raiser would need an entry in {@link #KEPT_UNRAISED} with its reason; there is none.</p>
 */
public class DocumentedErrorCodesAreRaisedTest {

    private static final String SOURCE_DIR = "net.vheerden.archi.mcp/src";

    /** Codes kept on purpose although no code returns them, each with its reason. */
    private static final Map<String, String> KEPT_UNRAISED = Map.of();

    private static final Pattern REFERENCE = Pattern.compile("(==\\s*|!=\\s*|equals\\(\\s*)?\\bErrorCode\\.([A-Z][A-Z0-9_]*)\\b");

    private static final Pattern CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");

    @Test
    public void everyDocumentedCode_shouldBeInTheEnum_andRaised_andEveryEnumCodeRaised() {
        Map<String, String> documented = new TreeMap<>();
        for (String code : tableCodes("docs/mcp-integration.md", "### Error Code Categories")) {
            documented.putIfAbsent(code, "docs/mcp-integration.md");
        }
        for (String code : tableCodes("docs/mutation-model.md", "### Common Error Codes")) {
            documented.putIfAbsent(code, "docs/mutation-model.md");
        }
        assertTrue("the doc tables yielded only " + documented.size() + " codes: " + documented.keySet(),
                documented.size() >= 30);

        Set<String> enumCodes = new TreeSet<>();
        Arrays.stream(ErrorCode.values()).forEach(c -> enumCodes.add(c.name()));
        Set<String> raised = raisedCodes();

        List<String> problems = new ArrayList<>();
        documented.forEach((code, file) -> {
            if (!enumCodes.contains(code)) {
                problems.add(file + " lists " + code + ", which is not an ErrorCode");
            } else if (!raised.contains(code) && !KEPT_UNRAISED.containsKey(code)) {
                problems.add(file + " lists " + code + ", which no code raises");
            }
        });
        for (String code : enumCodes) {
            if (!raised.contains(code) && !KEPT_UNRAISED.containsKey(code)) {
                problems.add("ErrorCode." + code + " is never raised");
            }
        }
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }

    @Test
    public void eachErrorCodeTable_shouldBeUnbroken() {
        for (String[] section : new String[][] {{"docs/mcp-integration.md", "### Error Code Categories"},
                {"docs/mutation-model.md", "### Common Error Codes"}}) {
            List<String> lines = section(section[0], section[1]);
            int first = -1;
            int last = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("|")) {
                    first = first < 0 ? i : first;
                    last = i;
                }
            }
            assertTrue(section[0] + " has no table under " + section[1], first >= 0);
            for (int i = first; i <= last; i++) {
                assertTrue(section[0] + ": the table under " + section[1] + " is split by the line \""
                        + lines.get(i) + "\"", lines.get(i).startsWith("|"));
            }
        }
    }

    @Test
    public void theRaisedScan_shouldNotCountAComparison() {
        Matcher m = REFERENCE.matcher("if (code == ErrorCode.A_B) x(ErrorCode.C_D); y.equals(ErrorCode.E_F);");
        List<String> counted = new ArrayList<>();
        while (m.find()) {
            if (m.group(1) == null) {
                counted.add(m.group(2));
            }
        }
        assertEquals(List.of("C_D"), counted);
    }

    /** The codes in the table rows of one section: every upper-snake token in a row's cells after the first. */
    private static Set<String> tableCodes(String file, String heading) {
        Set<String> codes = new TreeSet<>();
        for (String line : section(file, heading)) {
            if (!line.startsWith("|") || line.matches("\\|[\\s|:-]*")) {
                continue;
            }
            String[] cells = line.split("\\|");
            boolean categoryTable = heading.contains("Categories");
            // Categories table: | Category | Codes |. Common codes table: | `CODE` | Meaning |.
            String cell = categoryTable ? (cells.length > 2 ? cells[2] : "") : (cells.length > 1 ? cells[1] : "");
            Matcher m = CODE.matcher(cell);
            while (m.find()) {
                codes.add(m.group());
            }
        }
        return codes;
    }

    /** The lines from {@code heading} up to the next heading of the same or a higher level. */
    private static List<String> section(String file, String heading) {
        String[] lines = RepoFiles.read(file).split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : lines) {
            if (line.trim().equals(heading)) {
                in = true;
                continue;
            }
            if (in && (line.startsWith("### ") || line.startsWith("## "))) {
                break;
            }
            if (in) {
                out.add(line);
            }
        }
        if (!in) {
            throw new AssertionError(file + " has no heading " + heading);
        }
        return out;
    }

    private static Set<String> raisedCodes() {
        Set<String> raised = new TreeSet<>();
        for (String file : RepoFiles.under(SOURCE_DIR, ".java")) {
            if (file.endsWith("/response/ErrorCode.java")) {
                continue;
            }
            Matcher m = REFERENCE.matcher(JavaSourceText.blank(RepoFiles.read(file)));
            while (m.find()) {
                if (m.group(1) == null) {
                    raised.add(m.group(2));
                }
            }
        }
        return raised;
    }
}
