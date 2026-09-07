package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.model.LayoutQualityAssessor.CoverageDimension;
import net.vheerden.archi.mcp.model.LayoutQualityAssessor.MetricFindings;

/**
 * The registry of measured metrics, checked in BOTH directions against the surfaces it claims to
 * be answerable for.
 *
 * <p>The construction site of that registry states that it "is the one place a metric added later
 * has to be registered, and the parity pin over the result record's own components is what makes
 * forgetting it a red test rather than a silently narrower verdict". Until this class existed no
 * test in the tree mentioned the registry at all, so the comment described a guarantee nothing
 * enforced — and a metric id that resolved on no published register reached production and was
 * printed to callers, which is exactly what the absent guard would have caught.</p>
 *
 * <p><b>Both sides of every assertion here are derived.</b> The registry entries are scanned out of
 * the production source; the registers they are resolved against are read from the coverage
 * enumeration, from the violator-id registration sites and from the published result record's own
 * components. Nothing is typed here by hand, because a pin holding its own copy of the thing it is
 * pinning is green whatever the production code does — a failure this project has already paid for
 * once, on the guard that resolves suggestion-prose field names.</p>
 */
public class MetricFindingRegistryParityTest {

    private static final String ASSESSOR_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/LayoutQualityAssessor.java";

    /**
     * One registry entry as written in the production source: the id the prose prints and the
     * published component that carries the count.
     */
    private record RegisteredMetric(String metric, String field) {}

    /** Every {@code new MetricFinding(...)} registration, read from the production source. */
    private static List<RegisteredMetric> scanRegistry() {
        Matcher matcher = Pattern
                .compile("new MetricFinding\\(\"([A-Za-z0-9_]+)\", \"([A-Za-z0-9_]+)\"")
                .matcher(readSource(ASSESSOR_SOURCE));
        List<RegisteredMetric> registered = new ArrayList<>();
        while (matcher.find()) {
            registered.add(new RegisteredMetric(matcher.group(1), matcher.group(2)));
        }
        return registered;
    }

    /** The component names of the published assessment result. */
    private static Set<String> publishedComponents() {
        Set<String> published = new HashSet<>();
        for (RecordComponent component : LayoutAssessmentResult.class.getRecordComponents()) {
            published.add(component.getName());
        }
        return published;
    }

    /** The {@code int}-typed components of the published assessment result. */
    private static Set<String> publishedIntComponents() {
        Set<String> counts = new LinkedHashSet<>();
        for (RecordComponent component : LayoutAssessmentResult.class.getRecordComponents()) {
            if (component.getType() == int.class) {
                counts.add(component.getName());
            }
        }
        return counts;
    }

    /** Every violator-id key the assessor can register, read from its registration sites. */
    private static Set<String> violatorIdKeys() {
        Matcher matcher = Pattern.compile("violatorIds\\.put\\(\"([A-Za-z0-9_]+)\"")
                .matcher(readSource(ASSESSOR_SOURCE));
        Set<String> keys = new HashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    @Test
    public void everyRegisteredMetricId_resolvesOnAPublishedRegister() {
        // The id is printed to the caller inside a sentence that then tells them to go and read the
        // matching fields. An id present on no register is a token that names nothing they hold,
        // which is worse than silence: it costs them a lookup that cannot succeed.
        //
        // The requirement is that it resolves on ONE of the three registers, not that all ids come
        // from the same one. Most are coverage-dimension ids; a pin asserting that would be wrong,
        // because a metric with no dimension of its own has to fall back to its published field.
        Set<String> resolvable = new HashSet<>();
        for (CoverageDimension dimension : CoverageDimension.values()) {
            resolvable.add(dimension.id);
        }
        resolvable.addAll(violatorIdKeys());
        for (String component : publishedComponents()) {
            resolvable.add(component);
            if (component.endsWith("Count")) {
                resolvable.add(component.substring(0, component.length() - "Count".length()));
            }
        }

        List<RegisteredMetric> registered = scanRegistry();
        for (RegisteredMetric metric : registered) {
            assertTrue("the prose prints the metric id '" + metric.metric() + "', which is not a"
                    + " coverage-dimension id, not a violator-id key and not a component of the"
                    + " published result — the caller is handed a token that resolves nowhere",
                    resolvable.contains(metric.metric()));
        }

        // A regex that stopped matching would otherwise pass this test by checking nothing.
        assertTrue("no metric registrations were found — the scan has lost its target, so this"
                + " guard is certifying nothing", registered.size() >= 30);
    }

    @Test
    public void everyRegisteredMetric_namesAPublishedResultComponent() {
        // The field is what makes the reverse direction below checkable at all, so it has to be a
        // real component: a registry pointing at a field that does not exist would partition the
        // result against names nothing holds and report parity it never had.
        Set<String> published = publishedComponents();
        List<RegisteredMetric> registered = scanRegistry();

        for (RegisteredMetric metric : registered) {
            assertTrue("metric '" + metric.metric() + "' is registered against the published field"
                    + " '" + metric.field() + "', which is not a component of the assessment result",
                    published.contains(metric.field()));
        }
        assertTrue("no metric registrations were found — the scan has lost its target, so this"
                + " guard is certifying nothing", registered.size() >= 30);
    }

    /**
     * <b>What this partition does NOT cover.</b> It ranges over the {@code int} components of the
     * published result, so a finding published as a LIST rather than a count escapes it entirely.
     * {@code offCanvasWarnings} is that shape and is not registered; it is harmless for the verdict,
     * because it carries prose of its own and so cannot produce a false all-clear.
     *
     * <p>{@code connectionPassThroughs} was the second such escape and is no longer one: the
     * pass-through metric registers against {@code crossElementPassThroughCount}, an {@code int}
     * sibling this partition does see, which is also the quantity the rating is computed on — the
     * list beside it stays a capped description of what was seen and was never the count. The
     * escape was closed by publishing the count, not by excluding the list.</p>
     *
     * <p>Recorded here because a guard whose real reach is narrower than its name suggests is the
     * defect class this registry exists to close, and the next reader must not infer a guarantee
     * this makes no attempt to give.</p>
     */
    @Test
    public void everyCountValuedResultComponent_isEitherRegisteredOrDeclaredNotAFinding() {
        // THE GUARANTEE THE CONSTRUCTION SITE'S COMMENT MAKES. A count added to the published
        // result and forgotten in the registry narrows the verdict silently: the terminal sentence
        // is sourced from the registry, so it goes on stating that nothing was found on a dimension
        // that found something. Partitioning every int component into "registered" or "declared not
        // a finding" turns that omission into a red test, and forces the second case to be written
        // down where a reviewer sees it rather than left as an absence.
        Set<String> registeredFields = new HashSet<>();
        for (RegisteredMetric metric : scanRegistry()) {
            registeredFields.add(metric.field());
        }

        List<String> unaccounted = new ArrayList<>();
        for (String component : publishedIntComponents()) {
            if (!registeredFields.contains(component)
                    && !MetricFindings.NOT_MEASURED_AS_A_FINDING.contains(component)) {
                unaccounted.add(component);
            }
        }

        assertTrue("these count-valued components of the assessment result are neither registered"
                + " as measured metrics nor declared not to be findings, so the terminal verdict"
                + " cannot answer for them: " + unaccounted, unaccounted.isEmpty());
        assertTrue("no int components were found on the assessment result — the reflection has lost"
                + " its target, so this guard is certifying nothing",
                publishedIntComponents().size() >= 30);
    }

    @Test
    public void theTerminalDisclosureMustPrintTheMetricIdAndCountNeverTheRegisteredField() {
        // What makes re-pointing a registry entry's field a safe, caller-invisible change: the
        // published sentence is built from the metric id and the count, and never reads the field
        // at all. Scoped to the disclosure builder rather than the file, and read with comments
        // stripped so the prose explaining this rule cannot satisfy the scan for it.
        String code = withoutComments(readSource(ASSESSOR_SOURCE));
        int start = code.indexOf("for (MetricFinding finding : unexplained) {");
        assertTrue("the terminal disclosure no longer iterates the unexplained findings, so this"
                + " guard has lost its target", start >= 0);
        int end = code.indexOf("addTerminal", start);
        assertTrue("could not find the end of the disclosure builder", end > start);
        String builder = code.substring(start, end);

        assertTrue("the disclosure must name the metric id", builder.contains("finding.metric()"));
        assertTrue("the disclosure must carry the count", builder.contains("finding.count()"));
        assertTrue("the disclosure must not print the registered field name — it is an internal"
                + " pointer used to partition the result record, and printing it would make every"
                + " re-pointing a caller-visible change: " + builder,
                !builder.contains("finding.field()"));
    }

    @Test
    public void everyDeclaredNonFinding_namesAPublishedResultComponent() {
        // The exclusion set is half of the partition above, so a stale entry in it silently widens
        // the hole it exists to close: a component renamed away leaves an exclusion matching
        // nothing, and the new name is then unaccounted for with no test noticing the swap.
        Set<String> published = publishedComponents();

        for (String excluded : MetricFindings.NOT_MEASURED_AS_A_FINDING) {
            assertTrue("'" + excluded + "' is declared not to be a finding, but it is not a"
                    + " component of the published result — the exclusion names nothing",
                    published.contains(excluded));
        }
        assertTrue("the exclusion set is empty — every count-valued component would have to be"
                + " registered, which is not what the declaration says",
                !MetricFindings.NOT_MEASURED_AS_A_FINDING.isEmpty());
    }

    /** Every metric id recorded as explained, read from the production source. */
    private static Set<String> recordedAsExplained() {
        Matcher matcher = Pattern.compile("explained\\.add\\(\"([A-Za-z0-9_]+)\"\\)")
                .matcher(readSource(ASSESSOR_SOURCE));
        Set<String> recorded = new LinkedHashSet<>();
        while (matcher.find()) {
            recorded.add(matcher.group(1));
        }
        return recorded;
    }

    @Test
    public void everyMetricRecordedAsExplained_isAMetricTheRegistryActuallyHolds() {
        // The terminal disclosure names what this run measured MINUS what it recorded as explained,
        // and the two sides are matched on the metric id as a STRING. A typo on the recording side
        // therefore does not fail to compile and does not fail any prose assertion: the remedy is
        // still emitted, and the disclosure then names the same finding a second time, one line
        // below the sentence that already explained it.
        Set<String> registered = new HashSet<>();
        for (RegisteredMetric metric : scanRegistry()) {
            registered.add(metric.metric());
        }

        Set<String> recorded = recordedAsExplained();
        for (String metric : recorded) {
            assertTrue("a suggestion records '" + metric + "' as explained, but no such metric is"
                    + " registered — the recording matches nothing and the finding it was meant to"
                    + " account for will be disclosed as unexplained beside its own remedy",
                    registered.contains(metric));
        }
        assertTrue("no explained-metric recordings were found — the scan has lost its target, so"
                + " this guard is certifying nothing", recorded.size() >= 30);
    }

    @Test
    public void everyRegisteredMetric_hasSomeBranchThatCanAccountForIt() {
        // The other direction: a metric that is measured, registered, and recorded by no branch at
        // all can only ever reach the caller through the backstop, which offers no remedy. That is
        // the state this work existed to close, so a metric added later in that state must be a red
        // test rather than a quiet return to it.
        //
        // This says a branch EXISTS, not that it fires on every run. Several branches are
        // threshold-gated, which is exactly why the disclosure is sourced from what a run recorded
        // rather than from this set.
        Set<String> recorded = recordedAsExplained();
        List<String> unremedied = new ArrayList<>();
        for (RegisteredMetric metric : scanRegistry()) {
            if (!recorded.contains(metric.metric())) {
                unremedied.add(metric.metric());
            }
        }

        assertTrue("these registered metrics have no branch that can account for them, so a view"
                + " carrying one is told a dimension fired and given nothing to do about it: "
                + unremedied, unremedied.isEmpty());
    }

    /** Reads a production source file by walking up to the checkout root. */
    /** Delegates to the single comment-stripping scanner in the tree. */
    private static String withoutComments(String source) {
        return PassThroughAndCrowdingRemedyTest.withoutComments(source);
    }

    private static String readSource(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Paths.get("").toAbsolutePath());
    }
}
