package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Which label box each routing consumer reads, pinned on the shipped source: the label optimizer scores
 * the rendered box and only the rendered box, and the router's label-clearance shift reserves the raw
 * one. A value test cannot see a scorer that quietly goes back to the narrower box where no fixture puts
 * a blocker in the strip between the two.
 */
public class LabelBoxCallSiteTest {

    private static final String RENDERED = "LabelClearance.computeRenderedLabelRect(";
    private static final String RESERVED = "LabelClearance.computeReservedLabelRect(";

    @Test
    public void onlyTheOptimizerReadsTheRenderedBox_andOnlyTheClearanceShiftTheReservedOne() throws IOException {
        Map<String, List<String>> code = routingCode();

        assertEquals(Map.of("LabelPositionOptimizer.java", 1), callers(code, RENDERED));
        assertEquals(Map.of("RoutingPipeline.java", 1), callers(code, RESERVED));
    }

    @Test
    public void optimizerNeverReservesLabelWidth() throws IOException {
        List<String> optimizer = routingCode().get("LabelPositionOptimizer.java");

        assertTrue(optimizer.stream().noneMatch(l -> l.contains("reservedWidth(")));
        assertTrue(optimizer.stream().noneMatch(l -> l.contains(RESERVED)));
    }

    @Test
    public void routerRunsTheClearanceShiftOnTheNudgedPaths() throws IOException {
        List<String> pipeline = routingCode().get("RoutingPipeline.java");

        assertEquals(1, pipeline.stream()
                .filter(l -> l.contains("shiftPathsForLabelClearance(connections, nudgedPaths, allObstacles);"))
                .count());
    }

    /**
     * Where Archi draws a label ({@link LabelAnchor}) is read by the scoring side only: the rendered box
     * (the drawn line, a Middle label's point, a Source or Target label's box) and the optimizer's
     * hosting-segment orientation. The reserved box and the router's clearance shift keep the point a
     * fixed fraction along the centre path, so routes do not move with the anchor.
     */
    @Test
    public void onlyTheScoringSideReadsWhereArchiDrawsTheLabel() throws IOException {
        Map<String, List<String>> code = routingCode();

        assertEquals(Map.of("LabelClearance.java", 3, "LabelPositionOptimizer.java", 2),
                callers(code, "LabelAnchor."));
        assertEquals(Map.of("LabelClearance.java", 1), callers(code, "LabelAnchor.endpointLabelBox("));
        List<String> clearance = code.get("LabelClearance.java");
        assertTrue(body(clearance, "static RoutingRect computeRenderedLabelRect(").stream()
                .anyMatch(l -> l.contains("LabelAnchor.endpointLabelBox(")));
        assertTrue(body(clearance, "static RoutingRect computeRenderedLabelRect(").stream()
                .anyMatch(l -> l.contains("LabelAnchor.")));
        assertTrue(body(clearance, "static RoutingRect computeReservedLabelRect(").stream()
                .noneMatch(l -> l.contains("LabelAnchor.")));
        assertTrue(body(clearance, "private static RoutingRect labelRectAt(").stream()
                .noneMatch(l -> l.contains("LabelAnchor.")));
        assertTrue(code.get("RoutingPipeline.java").stream().noneMatch(l -> l.contains("LabelAnchor")));
    }

    /**
     * Whether a label lies on another connection's line is one rule, {@link ForeignLineHits#crosses}: the
     * optimizer scores with it and the assessor's detector counts with it, and neither tests a label against a
     * line any other way.
     */
    @Test
    public void optimizerAndAssessorTestALabelAgainstALineWithTheOneRule() throws IOException {
        Map<String, List<String>> code = routingCode();
        String crosses = "ForeignLineHits.crosses(";

        assertEquals(Map.of("LabelPositionOptimizer.java", 1), callers(code, crosses));
        assertTrue(code.get("LabelPositionOptimizer.java").stream()
                .noneMatch(l -> l.contains("lineSegmentIntersectsRect") || l.contains("segmentEntersOpenBox")));

        List<String> detector = body(withoutComments(locateRepoDirectory(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model").resolve("LayoutQualityAssessor.java")),
                "LabelOnForeignLineResult countLabelOnForeignLine(");
        assertEquals(1, detector.stream().filter(l -> l.contains(crosses)).count());
        assertTrue(detector.stream()
                .noneMatch(l -> l.contains("lineSegmentIntersectsRect") || l.contains("segmentEntersOpenBox")));
    }

    /** The lines of the method whose signature starts with {@code signature}, up to its closing brace. */
    private static List<String> body(List<String> lines, String signature) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(signature)) {
                start = i;
                break;
            }
        }
        assertTrue("no method " + signature, start >= 0);
        List<String> out = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            out.add(lines.get(i));
            if (lines.get(i).equals("    }")) {
                return out;
            }
        }
        throw new AssertionError("unterminated method " + signature);
    }

    /** Count of lines calling {@code call}, by file. */
    private static Map<String, Integer> callers(Map<String, List<String>> code, String call) {
        Map<String, Integer> out = new LinkedHashMap<>();
        code.forEach((file, lines) -> {
            int n = (int) lines.stream().filter(l -> l.contains(call)).count();
            if (n > 0) {
                out.put(file, n);
            }
        });
        return out;
    }

    /** The routing package's source, by file name, without comment lines. */
    private static Map<String, List<String>> routingCode() throws IOException {
        Path root = locateRepoDirectory("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/routing");
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                out.put(file.getFileName().toString(), withoutComments(file));
            }
        }
        assertTrue("scanned only " + out.size() + " files under " + root, out.size() > 10);
        return out;
    }

    /** The file's lines, without comment lines. */
    private static List<String> withoutComments(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (!(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** A repo-relative directory, found by walking up from the working directory. */
    private static Path locateRepoDirectory(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(relative))) {
                return dir.resolve(relative);
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
