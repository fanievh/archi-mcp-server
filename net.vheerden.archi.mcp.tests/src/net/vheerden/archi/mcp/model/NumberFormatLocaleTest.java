package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.CostEstimator;
import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;

/**
 * A number in a response reads the same on a German or an Arabic number format as on an English one.
 *
 * <p>Only the FORMAT category decides how {@code String.format} writes a number, so each pin forces
 * that category alone: {@code de-DE} turns {@code %.1f} of 1.5 into {@code 1,5}; {@code ar} also
 * writes {@code %d} and {@code %f} in Arabic-Indic digits (U+0660 to U+0669). Each text is compared
 * with the same call under English and must be pure ASCII, so a pin cannot pass because both calls
 * happened to run in the same locale. The default and both categories are restored unconditionally,
 * because the harness runs every class in one JVM.</p>
 */
public class NumberFormatLocaleTest {

    static final Locale GERMAN = Locale.forLanguageTag("de-DE");
    static final Locale ARABIC = Locale.forLanguageTag("ar");

    private Locale savedDefault;
    private Locale savedDisplay;
    private Locale savedFormat;

    @Before
    public void saveLocale() {
        savedDefault = Locale.getDefault();
        savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(savedDefault);
        Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
        Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    }

    // ---- the structured end-batch field ------------------------------------------------------

    @Test
    public void shouldWriteTheBatchDurationAsOnEnglish() throws Exception {
        Method format = MutationContext.class.getDeclaredMethod("formatDuration", Duration.class);
        format.setAccessible(true);
        for (Locale locale : List.of(GERMAN, ARABIC)) {
            MutationContext context = new MutationContext();
            context.beginBatch("number format");
            Field started = MutationContext.class.getDeclaredField("batchStarted");
            started.setAccessible(true);
            // At least a second back, so the summaries take the decimal branch. The elapsed time
            // keeps running, so the summaries are held to the shape and the exact text is compared
            // on a fixed duration below.
            started.set(context, Instant.now().minusMillis(1500));

            force(locale);
            String tag = locale.toLanguageTag();
            for (String duration : List.of(context.buildCommitSummary().duration(),
                    context.buildRollbackSummary().duration())) {
                assertTrue("under " + tag + " the duration must read like 1.5s: " + duration,
                        duration.matches("[0-9]+\\.[0-9]s"));
            }
            Supplier<String> fixed = () -> {
                try {
                    return (String) format.invoke(context, Duration.ofMillis(1500));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            };
            assertAsOnEnglish(fixed.get(), underEnglish(fixed));
        }
    }

    // ---- assess-layout suggestions -----------------------------------------------------------

    @Test
    public void shouldWriteTheHubPortSuggestionAsOnEnglish() {
        force(GERMAN);
        String forced = hubPortSuggestion();
        String english = underEnglish(NumberFormatLocaleTest::hubPortSuggestion);
        assertAsOnEnglish(forced, english);
    }

    private static String hubPortSuggestion() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("hub", 1000, 1000, 200, 400));
        for (int i = 0; i < 6; i++) {
            double y = 200 * i;
            nodes.add(node("spoke" + i, 300, y, 120, 60));
            connections.add(new AssessmentConnection("spokeConn" + i, "spoke" + i, "hub",
                    List.of(p(420, y + 30), p(700, y + 30), p(700, 1200), p(1000, 1200)), "", 1));
        }
        String suggestion = new LayoutQualityAssessor().assess(nodes, connections, false).suggestions().stream()
                .filter(s -> s.startsWith("Hub-port allocation quality is"))
                .findFirst().orElse(null);
        assertNotNull("the fixture must still raise the hub-port suggestion", suggestion);
        return suggestion;
    }

    // ---- the integer-only families: only a digit locale can move them ------------------------

    @Test
    public void shouldWriteTheHubSizingSuggestionsAsOnEnglish() {
        List<HubElementEntryDto> entries = List.of(
                new HubElementEntryDto("vo-hub", "e-hub", "Hub", "ApplicationComponent", 14, 120, 55, 300));
        force(ARABIC);
        List<String> forced = HubSizingSuggestionBuilder.buildSuggestions(entries);
        List<String> english = underEnglish(() -> HubSizingSuggestionBuilder.buildSuggestions(entries));
        assertEquals("both suggestions, the label-adjusted clause and the 2D one", 2, english.size());
        assertAsOnEnglish(forced, english);
    }

    @Test
    public void shouldWriteEveryCostRecommendationAsOnEnglish() {
        int[][] cases = {
                {5, 500, 300},          // small
                {30, 5000, 2000},       // medium
                {247, 15438, 4000},     // large, with a saving
                {247, 15438, 15438},    // large, no saving
        };
        force(ARABIC);
        List<String> forced = new ArrayList<>();
        for (int[] c : cases) {
            forced.add(CostEstimator.buildRecommendation(c[0], c[1], FieldPreset.STANDARD, c[2]));
        }
        List<String> english = underEnglish(() -> {
            List<String> out = new ArrayList<>();
            for (int[] c : cases) {
                out.add(CostEstimator.buildRecommendation(c[0], c[1], FieldPreset.STANDARD, c[2]));
            }
            return out;
        });
        assertAsOnEnglish(forced, english);
    }

    @Test
    public void shouldWriteTheDefaultResolutionReasonsAsOnEnglish() {
        Supplier<List<String>> reasons = () -> List.of(
                ArrangeGroupsDefaultResolutionDecision.decide(null, 20, 3, true, true, false).reason(),
                AdjustViewSpacingDefaultResolutionDecision.decide(null, 5, 0, 20, 40, true, true, false).reason());
        force(ARABIC);
        assertAsOnEnglish(reasons.get(), underEnglish(reasons));
    }

    @Test
    public void shouldWriteTheReflowOfferAsOnEnglish() {
        Supplier<String> offer = () -> SpacingPreconditionInfeasibilityCertificate.buildReflowOffer(
                41.6, 37.2, 240, 175, 14);
        force(ARABIC);
        assertAsOnEnglish(offer.get(), underEnglish(offer));
    }

    // ---- decimals on a comma locale ----------------------------------------------------------

    @Test
    public void shouldWriteTheImageCoverageWarningAsOnEnglish() {
        Supplier<List<String>> warnings = () -> List.of(
                ImageHelper.coverageWarning(45.25, false, null),
                ImageHelper.coverageWarning(45.25, true, "fill"));
        force(GERMAN);
        assertAsOnEnglish(warnings.get(), underEnglish(warnings));
    }

    @Test
    public void shouldWriteTheCrossingInflationWarningAsOnEnglish() {
        Supplier<String> warning = () -> RoutingPipeline.buildCrossingInflationWarning(17, 8);
        force(GERMAN);
        assertAsOnEnglish(warning.get(), underEnglish(warning));
    }

    // ---- shared helpers ----------------------------------------------------------------------

    /**
     * Forces the FORMAT category and proves it took: without the check a pin run on a JVM that
     * ignored the call would compare English with English and pass. Under {@code ar} it also
     * proves the digits changed, because the integer-only pins rely on nothing else.
     */
    static void force(Locale locale) {
        Locale.setDefault(Locale.Category.FORMAT, locale);
        assertLocaleTook(locale);
    }

    static void assertLocaleTook(Locale locale) {
        assertNotEquals("the FORMAT locale did not take: " + locale.toLanguageTag(),
                "1.5", String.format("%.1f", 1.5));
        if (locale.equals(ARABIC)) {
            assertNotEquals("the FORMAT locale writes ASCII digits, so an integer pin would pass "
                    + "without testing anything: " + locale.toLanguageTag(),
                    "5", String.format("%d", 5));
        }
    }

    /** Runs {@code call} with the FORMAT category set to English, then puts the forced locale back. */
    static <T> T underEnglish(Supplier<T> call) {
        Locale forced = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.ENGLISH);
        try {
            return call.get();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, forced);
        }
    }

    static void assertAsOnEnglish(Object forced, Object english) {
        String tag = Locale.getDefault(Locale.Category.FORMAT).toLanguageTag();
        assertEquals("under " + tag + " the text must read as on English", english, forced);
        assertTrue("under " + tag + " the numbers must be ASCII: " + forced, hasOnlyAsciiNumbers(String.valueOf(forced)));
    }

    private static boolean hasOnlyAsciiNumbers(String text) {
        // The English texts carry a few non-ASCII symbols of their own (an em dash, a times sign);
        // what may not appear is a non-ASCII digit or the Arabic decimal separator U+066B.
        return text.chars().noneMatch(c -> Character.isDigit(c) && c > 127 || c == 0x066B);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    private static double[] p(double x, double y) {
        return new double[] {x, y};
    }
}
