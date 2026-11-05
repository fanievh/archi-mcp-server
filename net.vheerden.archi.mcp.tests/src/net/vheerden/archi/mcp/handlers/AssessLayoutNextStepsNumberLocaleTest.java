package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * The numbers in {@code assess-layout}'s next steps read the same on a German or an Arabic number
 * format as on an English one.
 *
 * <p>Each pin forces the FORMAT category alone, compares the step with the same call under English,
 * and requires its digits to be ASCII. {@code de-DE} moves only the decimal steps; {@code ar} moves
 * the integer ones as well, so the integer step is pinned under {@code ar}. The default and both
 * categories are restored unconditionally.</p>
 */
public class AssessLayoutNextStepsNumberLocaleTest {

    private static final Locale GERMAN = Locale.forLanguageTag("de-DE");
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    private final ViewPlacementHandler handler = new ViewPlacementHandler(
            new BaseTestAccessor(), new ResponseFormatter(), new CommandRegistry(), null);

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

    @Test
    public void shouldWriteTheHubPortScoreAsOnEnglish() {
        AssessLayoutResultDto dto = ViewPlacementHandlerTest.withComponent(base(), "hubPortQualityScore", 0.62);
        for (Locale locale : List.of(GERMAN, ARABIC)) {
            assertStepAsOnEnglish(locale, () -> step(dto, "Hub-port quality "));
        }
    }

    @Test
    public void shouldWriteTheSaturatedLayoutNumbersAsOnEnglish() {
        // The sparse arm writes both the corridor score (two decimals) and the clearance and its
        // floor (no decimals, so only a digit locale can move them).
        AssessLayoutResultDto dto = ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(
                        ViewPlacementHandlerTest.withComponent(base(), "corridorUtilisationScore", 0.93),
                        "coincidentSegmentCount", 5),
                "hubNeighbourClearanceMin", 72.4);
        for (Locale locale : List.of(GERMAN, ARABIC)) {
            assertStepAsOnEnglish(locale, () -> step(dto, "Saturated layout "));
        }
    }

    @Test
    public void shouldWriteTheBoundaryViolationCountAsOnEnglish() {
        AssessLayoutResultDto dto = ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(base(), "boundaryViolations",
                        List.of("child outside parent")),
                "boundaryViolationCount", 12);
        assertStepAsOnEnglish(ARABIC, () -> step(dto, "Found "));
    }

    private void assertStepAsOnEnglish(Locale locale, Supplier<String> call) {
        Locale.setDefault(Locale.Category.FORMAT, locale);
        assertNotEquals("the FORMAT locale did not take: " + locale.toLanguageTag(),
                "1.5", String.format("%.1f", 1.5));
        if (locale.equals(ARABIC)) {
            // The integer step is pinned under ar alone, so it must be a locale that moves digits.
            assertNotEquals("the FORMAT locale writes ASCII digits: " + locale.toLanguageTag(),
                    "5", String.format("%d", 5));
        }
        String forced = call.get();
        Locale.setDefault(Locale.Category.FORMAT, Locale.ENGLISH);
        String english = call.get();
        Locale.setDefault(Locale.Category.FORMAT, locale);

        String tag = locale.toLanguageTag();
        assertEquals("under " + tag + " the step must read as on English", english, forced);
        // The English step carries non-ASCII symbols of its own (an em dash, a times sign); what may
        // not appear is a non-ASCII digit or the Arabic decimal separator U+066B.
        assertTrue("under " + tag + " the numbers must be ASCII: " + forced,
                forced.chars().noneMatch(c -> Character.isDigit(c) && c > 127 || c == 0x066B));
    }

    private String step(AssessLayoutResultDto dto, String prefix) {
        String step = handler.buildAssessLayoutNextSteps(dto).stream()
                .filter(s -> s.startsWith(prefix))
                .findFirst().orElse(null);
        assertNotNull("the fixture must still raise the step starting '" + prefix + "'", step);
        return step;
    }

    private static AssessLayoutResultDto base() {
        return new AssessLayoutResultDto(
                "v-1", 5, 0, 0, 0, 0, 0.0, 145.0, 100, "fair", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "fair", "fair",
                1.0, null);
    }
}
