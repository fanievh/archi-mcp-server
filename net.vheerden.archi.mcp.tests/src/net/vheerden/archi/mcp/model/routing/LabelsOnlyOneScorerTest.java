package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The labels-only pass has one scorer, the label optimizer's: it compares the labels as they stand with the best trial
 * and with the best anchors in place through {@link LabelPositionOptimizer#scoreAssignment}, and no score loop or label-box builder exists outside the
 * routing package. Pinned on the shipped source, because a value test cannot tell a copied loop from the original
 * while the two still agree.
 */
public class LabelsOnlyOneScorerTest {

    private static final String MODEL = "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model";
    /** The model-side files the labels-only path runs through. */
    private static final List<String> PATH_FILES = List.of("LabelOptimizationPass.java", "LabelsOnlyRoute.java",
            "AutoRoutePlan.java");
    private static final List<String> SCORER_NAMES = List.of("scorePosition", "computeTotalScore", "scoreAssignment",
            "scoredLabelRect", "computeRenderedLabelRect", "runGreedyPass", "foreignLinePenalty");

    @Test
    public void theLabelsOnlyEntryComparesWithTheBestTrialThroughTheOptimizersScorer() throws IOException {
        List<String> entry = body(code(model().resolve("LabelOptimizationPass.java")),
                "static LabelsOnlyResult computeLabelsOnly(");

        // Two assignments are scored besides the best trial: the labels as they stand, and the positions they hold
        // with the best anchors in place.
        assertEquals(2, entry.stream().filter(l -> l.contains("optimizer.scoreAssignment(")).count());
        assertEquals(1, entry.stream().filter(l -> l.contains("optimizer.optimizeMultiTrial(")).count());
        assertEquals(1, entry.stream().filter(l -> l.contains("optimizer.computeOffsetsForPositions(")).count());
    }

    @Test
    public void theLabelsOnlyPathAccumulatesNoScoreAndBuildsNoLabelBox() throws IOException {
        Pattern accumulates = Pattern.compile("\\+=\\s*[0-9]");
        List<String> found = new ArrayList<>();
        for (String name : PATH_FILES) {
            List<String> lines = code(model().resolve(name));
            for (String line : lines) {
                if (accumulates.matcher(line).find() || line.contains("insetRectOverlap")
                        || line.contains("isWithinProximity") || line.contains("LabelBoxEstimate")
                        || line.contains("ForeignLineHits") || line.contains("LabelClearance")) {
                    found.add(name + ": " + line.strip());
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    public void noScorerIsDeclaredOutsideTheRoutingPackage() throws IOException {
        Pattern declares = Pattern.compile(
                "^\\s*(?:(?:public|private|protected|static|final|synchronized)\\s+)*[\\w<>\\[\\], ?.]+\\s+("
                        + String.join("|", SCORER_NAMES) + ")\\s*\\(");
        Path routing = model().resolve("routing");
        List<String> found = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(model())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(routing)).sorted()
                    .toList()) {
                scanned++;
                for (String line : code(file)) {
                    Matcher m = declares.matcher(line);
                    if (m.find()) {
                        found.add(file.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue("scanned only " + scanned + " files", scanned > 100);
        assertEquals(List.of(), found);
    }

    /** The lines of the method whose signature contains {@code signature}, up to its closing brace. */
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

    /** The file's lines, without comment lines. */
    private static List<String> code(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (!(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Path model() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(MODEL))) {
                return dir.resolve(MODEL);
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + MODEL + " from " + Path.of("").toAbsolutePath());
    }
}
