package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.RepoFiles;
import net.vheerden.archi.mcp.registry.ResourceRegistry;

/**
 * The tool and resource counts the README and the unreleased CHANGELOG section state are the counts
 * the server registers.
 *
 * <p>Only the README and the CHANGELOG's {@code ## v1.10.0} section are read: a released section is
 * history and keeps the count it shipped with. In them, "N MCP tools" or "N tools" is the whole surface
 * or the read-only one; "the N that could change" is the count a read-only server leaves out; "N MCP
 * resources" or "N resources" is the resource count; a README {@code ### Category (N tools)} heading is
 * the number of tool rows in that section's table. A number after "was" is a past count, not a claim
 * about now. Tool counts come from {@link ToolAccess#table()}; the resource count from registering
 * every resource the server defines.</p>
 */
public class PublishedCountsMatchTheRegistryTest {

    private static final String CHANGELOG_SECTION = "## v1.10.0";

    private static final Pattern HEADING = Pattern.compile("^### (.+) \\((\\d+) tools?\\)\\s*$");
    private static final Pattern TOOLS = Pattern.compile("(?<![\\w.])(\\d+)\\s+(?:MCP\\s+)?tools\\b");
    private static final Pattern RESOURCES = Pattern.compile("(?<![\\w.])(\\d+)\\s+(?:MCP\\s+)?resources\\b");
    private static final Pattern COULD_CHANGE = Pattern.compile("\\bthe (\\d+) that could change\\b");
    private static final Pattern ROW = Pattern.compile("^\\| `([a-z]+(?:-[a-z0-9]+)*)` \\|");

    @Test
    public void everyCountClaim_shouldMatchWhatTheServerRegisters() {
        int total = ToolAccess.table().size();
        int read = (int) ToolAccess.table().values().stream().filter(e -> e.access() == ToolAccess.READ).count();
        int write = total - read;
        int resources = resourceCount();

        List<String> problems = new ArrayList<>();
        int[] matched = {0};
        check("README.md", RepoFiles.read("README.md").split("\n", -1), 1, total, read, write, resources,
                problems, matched);
        String changelog = RepoFiles.read("CHANGELOG.md");
        // The heading may carry a suffix ("## v1.10.0 — DRAFT (unreleased)")
        int heading = changelog.indexOf("\n" + CHANGELOG_SECTION);
        assertTrue("CHANGELOG.md has no " + CHANGELOG_SECTION + " section", heading >= 0);
        int start = heading + 1;
        int end = changelog.indexOf("\n## ", start + CHANGELOG_SECTION.length());
        String section = changelog.substring(start, end < 0 ? changelog.length() : end);
        int firstLine = (int) changelog.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
        check("CHANGELOG.md", section.split("\n", -1), firstLine, total, read, write, resources, problems, matched);

        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
        assertTrue("only " + matched[0] + " count claims were found", matched[0] >= 5);
    }

    @Test
    public void theReadmeToolSections_shouldListEveryRegisteredToolOnce() {
        Set<String> rows = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        boolean inSection = false;
        for (String line : RepoFiles.read("README.md").split("\n", -1)) {
            if (line.startsWith("## ") || line.startsWith("### ")) {
                inSection = HEADING.matcher(line).matches();
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (inSection && row.find() && !rows.add(row.group(1))) {
                duplicates.add(row.group(1));
            }
        }
        assertEquals("tools listed twice", List.of(), duplicates);
        assertEquals(new TreeSet<>(ToolAccess.table().keySet()), rows);
    }

    private static void check(String file, String[] lines, int firstLine, int total, int read, int write,
            int resources, List<String> problems, int[] matched) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String where = file + ":" + (firstLine + i);
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int rows = rowsUnder(lines, i);
                matched[0]++;
                if (Integer.parseInt(heading.group(2)) != rows) {
                    problems.add(where + " heading \"" + line + "\" claims " + heading.group(2) + ", its table has "
                            + rows);
                }
                continue;
            }
            Matcher tools = TOOLS.matcher(line);
            while (tools.find()) {
                if (afterWas(line, tools.start())) {
                    continue;
                }
                matched[0]++;
                int n = Integer.parseInt(tools.group(1));
                if (n != total && n != read) {
                    problems.add(where + " says \"" + tools.group() + "\"; the server has " + total + " tools, "
                            + read + " of them read-only");
                }
            }
            Matcher change = COULD_CHANGE.matcher(line);
            while (change.find()) {
                matched[0]++;
                if (Integer.parseInt(change.group(1)) != write) {
                    problems.add(where + " says \"" + change.group() + "\"; " + write + " tools can change");
                }
            }
            Matcher res = RESOURCES.matcher(line);
            while (res.find()) {
                if (afterWas(line, res.start())) {
                    continue;
                }
                matched[0]++;
                if (Integer.parseInt(res.group(1)) != resources) {
                    problems.add(where + " says \"" + res.group() + "\"; the server has " + resources + " resources");
                }
            }
        }
    }

    private static boolean afterWas(String line, int at) {
        return line.substring(0, at).matches("(?s).*\\bwas\\s+$");
    }

    private static int rowsUnder(String[] lines, int heading) {
        int rows = 0;
        for (int i = heading + 1; i < lines.length; i++) {
            if (lines[i].startsWith("## ") || lines[i].startsWith("### ")) {
                break;
            }
            if (ROW.matcher(lines[i]).find()) {
                rows++;
            }
        }
        return rows;
    }

    /** Registers every resource the server defines, with each body stubbed so none is skipped. */
    private static int resourceCount() {
        ResourceRegistry registry = new ResourceRegistry();
        new ResourceHandler() {
            @Override
            String loadResourceFile(String filePath) {
                return "# " + filePath + "\n";
            }
        }.registerResources(registry);
        return registry.getResourceCount();
    }
}
