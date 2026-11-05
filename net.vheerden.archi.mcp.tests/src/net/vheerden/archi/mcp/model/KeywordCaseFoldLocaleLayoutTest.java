package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code direction: "right"} on the two {@code auto-layout-and-route} paths that run a real assessment,
 * under a Turkish default locale: grouped mode, and auto mode with a {@code targetRating}.
 *
 * <p>Grouped mode accepted a lower-case {@code "right"} on {@code tr} but upper-cased it in the default
 * locale and reported {@code direction: "RİGHT"}; the layout itself matched English, because the
 * arrangement test compares with {@code equalsIgnoreCase}, which folds per character. These pins hold
 * both the report and the layout to the English result. They need a display, like this tool's other
 * end-to-end pins; {@link KeywordCaseFoldLocaleTest} holds the headless ones.</p>
 */
public class KeywordCaseFoldLocaleLayoutTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locale before;
    private final List<EchoToolHarness> harnesses = new ArrayList<>();

    @Before
    public void forceTurkishLocale() {
        before = Locale.getDefault();
        Locale.setDefault(TURKISH);
    }

    @After
    public void restoreLocale() {
        try {
            harnesses.forEach(EchoToolHarness::dispose);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void shouldLayOutAndReportALowerCaseRightDirection_inGroupedMode_asOnEnglish() {
        Outcome turkish = run("mode", "grouped");
        Outcome english = english(() -> run("mode", "grouped"));

        assertEquals("RIGHT", turkish.direction);
        assertEquals(english.direction, turkish.direction);
        assertEquals(english.layout, turkish.layout);
    }

    @Test
    public void shouldReportALowerCaseRightDirection_withATargetRating_asOnEnglish() {
        Outcome turkish = run("targetRating", "fair");
        Outcome english = english(() -> run("targetRating", "fair"));

        assertEquals("RIGHT", turkish.direction);
        assertEquals(english.direction, turkish.direction);
    }

    private record Outcome(String direction, List<String> layout) {
    }

    private Outcome run(String key, String value) {
        EchoToolHarness h;
        try {
            h = new EchoToolHarness("model-keyword-case-fold-layout-" + harnesses.size());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        harnesses.add(h);
        IDiagramModelGroup g1 = h.f.group(h.f.view, "g1", 40, 300, 400, 300);
        IDiagramModelGroup g2 = h.f.group(h.f.view, "g2", 500, 300, 400, 300);
        IArchimateElement e1 = h.f.element("e1");
        IArchimateElement e3 = h.f.element("e3");
        IDiagramModelArchimateObject o1 = h.f.object(g1, "o1", e1, 10, 30, 120, 55);
        h.f.object(g1, "o2", h.f.element("e2"), 10, 130, 120, 55);
        IDiagramModelArchimateObject o3 = h.f.object(g2, "o3", e3, 10, 30, 120, 55);
        h.f.object(g2, "o4", h.f.element("e4"), 10, 130, 120, 55);
        h.f.archimateConnection("conn-13", h.f.relationship("rel-13", e1, e3), o1, o3);

        JsonNode root = h.success("auto-layout-and-route", "viewId", h.f.view.getId(), "direction", "right",
                key, value);
        List<String> layout = new ArrayList<>();
        collect(h.f.view, layout);
        return new Outcome(root.path("result").path("direction").asText(), layout);
    }

    private static void collect(IDiagramModelContainer parent, List<String> out) {
        for (IDiagramModelObject o : parent.getChildren()) {
            out.add(o.getId() + "@" + o.getBounds().getX() + "," + o.getBounds().getY() + ","
                    + o.getBounds().getWidth() + "x" + o.getBounds().getHeight());
            if (o instanceof IDiagramModelContainer c) {
                collect(c, out);
            }
        }
    }

    private static <T> T english(Supplier<T> body) {
        Locale.setDefault(Locale.ENGLISH);
        try {
            return body.get();
        } finally {
            Locale.setDefault(TURKISH);
        }
    }
}
