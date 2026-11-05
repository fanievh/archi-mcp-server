package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.RepoFiles;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Every tool name the docs cite is a tool the server registers, or a {@code bulk-mutate} operation.
 *
 * <p>The docs are scanned for backticked kebab-case tokens ({@code `get-element`}) whose first word is
 * the verb of some registered tool or operation ({@code get}, {@code add}, {@code update}, ...). Each
 * such token must be a registered tool, a {@code bulk-mutate} operation, or an entry in the allow-list
 * below, which gives each entry its reason. A retired tool may be named only on a line that says it
 * was removed, so the docs can say a tool is gone but cannot go on describing it.</p>
 *
 * <p><strong>Blind spot.</strong> The verb filter is what keeps option values and file names out of
 * the scan, and it is also what this gate cannot see past: a cited name whose first word is no
 * registered tool's verb ({@code decide-mutation}, a misspelt verb such as {@code gte-element}) is not
 * checked. A name that is not backticked is not checked either.</p>
 */
public class CitedToolNamesExistTest {

    /** Retired tools: named only where the line says they are gone. */
    private static final Map<String, String> RETIRED = Map.of(
            "set-approval-mode", "removed when approval mode became the human's to set, in Archi",
            "decide-mutation", "removed when approving a change became the human's, in Archi");

    /** Tokens that look like tool names but are values. */
    private static final Map<String, String> VALUES = Map.of(
            "auto-hide-on-collision", "a label policy value of auto-route-connections",
            "auto-resize-not-requested", "a reason value the layout reports when it did not resize");

    private static final Pattern RETIRED_LINE = Pattern.compile("(?i)removed|retired|no longer");

    private static final Pattern TOKEN = Pattern.compile("`([a-z]+(?:-[a-z0-9]+)+)`");

    /** The docs scanned, by repo-relative path. */
    static List<String> scannedFiles() {
        List<String> files = new ArrayList<>(List.of("README.md", "CHANGELOG.md", "SECURITY.md", "BUILDING.md"));
        files.removeIf(f -> !RepoFiles.root().resolve(f).toFile().isFile());
        files.addAll(RepoFiles.under("docs", ".md"));
        files.addAll(RepoFiles.under("net.vheerden.archi.mcp/resources", ".md"));
        return files;
    }

    @Test
    public void everyCitedToolName_shouldBeARegisteredToolOrOperation() {
        Set<String> registered = new TreeSet<>(ToolAccess.table().keySet());
        registered.addAll(BulkOperation.SUPPORTED_TOOLS);
        Set<String> verbs = new TreeSet<>();
        for (String name : registered) {
            verbs.add(name.split("-", 2)[0]);
        }

        Set<String> realCited = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (String file : scannedFiles()) {
            String[] lines = RepoFiles.read(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TOKEN.matcher(lines[i]);
                while (m.find()) {
                    String token = m.group(1);
                    if (!verbs.contains(token.substring(0, token.indexOf('-')))) {
                        continue;
                    }
                    String where = file + ":" + (i + 1);
                    if (registered.contains(token)) {
                        realCited.add(token);
                    } else if (RETIRED.containsKey(token)) {
                        if (!RETIRED_LINE.matcher(lines[i]).find()) {
                            problems.add(where + " names the retired tool `" + token
                                    + "` without saying it was removed");
                        }
                    } else if (!VALUES.containsKey(token)) {
                        problems.add(where + " cites `" + token + "`, which is not a registered tool or operation");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " cited tool name(s) do not exist:\n  " + String.join("\n  ", problems));
        }
        assertTrue("the scan recognised only " + realCited.size() + " distinct registered tool names: " + realCited,
                realCited.size() >= 60);
    }
}
