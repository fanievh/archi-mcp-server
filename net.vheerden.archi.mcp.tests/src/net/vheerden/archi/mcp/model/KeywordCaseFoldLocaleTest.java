package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * A keyword argument reads the same on a Turkish default locale as on an English one.
 *
 * <p>Under {@code tr} (and {@code az}), {@code "I".toLowerCase()} is a dotless {@code ı} and
 * {@code "i".toUpperCase()} a dotted {@code İ}. A keyword folded in the default locale therefore stops
 * matching its own spelling: {@code fontStyle: "Italic"} was refused, {@code direction: "right"} was
 * refused by the ELK path and reported as {@code RİGHT} by grouped mode. Every pin here runs with the
 * default locale set to {@code tr-TR} and compares against the same call under English; the locale is
 * restored unconditionally, because the harness runs every class in one JVM.</p>
 *
 * <p>The one control runs the other way: name search folds the user's own text in the user's own
 * locale on purpose, so under {@code tr} it keeps the Turkish fold.</p>
 */
public class KeywordCaseFoldLocaleTest {

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

    // ---- the styling triples: validate, map, report -------------------------------------------

    @Test
    public void shouldReadEveryStylingKeyword_asOnEnglish() throws Exception {
        // figureType, verticalTextAlignment and borderType accept no value with an I or i, so their rows
        // cannot fail today; they hold those three folds to the English answer if the vocabulary grows.
        String[][] cases = {
                {"figureType", "Rectangular"},
                {"textAlignment", "RIGHT"},
                {"textAlignment", "Right"},
                {"verticalTextAlignment", "Bottom"},
                {"fontStyle", "Italic"},
                {"fontStyle", "BOLD-ITALIC"},
                {"lineStyle", "SOLID"},
                {"gradient", "LEFT-RIGHT"},
                {"gradient", "RIGHT-LEFT"},
                {"borderType", "DogEar"},
        };
        List<String> wrong = new ArrayList<>();
        for (String[] c : cases) {
            String key = c[0];
            String value = c[1];
            try {
                validate(key, value);
            } catch (RuntimeException e) {
                wrong.add(key + " " + value + ": validate refused: " + e.getMessage());
                continue;
            }
            Object mapped = map(key, value);
            Object englishMapped = english(() -> map(key, value));
            if (!englishMapped.equals(mapped)) {
                wrong.add(key + " " + value + ": mapped to " + mapped + ", English " + englishMapped);
            }
            String reported = report(key, value);
            String englishReported = english(() -> report(key, value));
            if (!String.valueOf(englishReported).equals(String.valueOf(reported))) {
                wrong.add(key + " " + value + ": reported " + visible(String.valueOf(reported))
                        + ", English " + englishReported);
            }
        }
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    @Test
    public void shouldAcceptAnItalicFontStyle_onUpdateViewObject() {
        EchoToolHarness h = harness();

        JsonNode root = h.success("update-view-object", "viewObjectId", "vo-a", "fontStyle", "Italic");

        assertEquals("italic", root.path("result").path("fontStyle").asText());
        assertTrue("read back as italic: " + h.f.serve(Map.of()),
                h.f.serve(Map.of()).contains("\"fontStyle\":\"italic\""));
    }

    @Test
    public void shouldAcceptAnItalicFontStyle_onBulkMutate() {
        EchoToolHarness h = harness();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewObjectId", "vo-a");
        params.put("fontStyle", "Italic");

        BulkMutationResult result = h.f.accessor.executeBulk(ViewContentsFixture.SESSION,
                List.of(new BulkOperation("update-view-object", params)), null, false);

        assertTrue(result.allSucceeded());
        assertTrue("read back as italic: " + h.f.serve(Map.of()),
                h.f.serve(Map.of()).contains("\"fontStyle\":\"italic\""));
    }

    // ---- direction --------------------------------------------------------------------------

    @Test
    public void shouldAcceptALowerCaseRightDirection_inTheElkEngine() {
        List<LayoutNode> nodes = List.of(new LayoutNode("n0", 50, 50, 120, 55, null),
                new LayoutNode("n1", 200, 50, 120, 55, null), new LayoutNode("n2", 350, 50, 120, 55, null));
        List<LayoutEdge> edges = List.of(new LayoutEdge("n0", "n1", null), new LayoutEdge("n1", "n2", null));

        ElkLayoutResult result = new ElkLayoutEngine().computeLayout(nodes, edges, "right", 50);
        ElkLayoutResult englishResult = english(() -> new ElkLayoutEngine().computeLayout(nodes, edges, "right", 50));

        assertEquals(englishResult.positions(), result.positions());
    }

    @Test
    public void shouldLayOutAndReportALowerCaseRightDirection_asOnEnglish() {
        LaidOut turkish = autoLayoutRight();
        LaidOut english = english(this::autoLayoutRight);

        assertEquals("RIGHT", turkish.direction());
        assertEquals(english.direction(), turkish.direction());
        assertEquals(english.layout(), turkish.layout());
    }

    // ---- arrangement ------------------------------------------------------------------------

    @Test
    public void shouldAcceptAnUpperCaseGridArrangement_onLayoutWithinGroup() {
        EchoToolHarness h = groupedHarness();

        JsonNode root = h.success("layout-within-group", "viewId", h.f.view.getId(),
                "groupViewObjectId", "g1", "arrangement", "GRID");

        assertEquals("grid", root.path("result").path("arrangement").asText());
    }

    @Test
    public void shouldAcceptAnUpperCaseGridArrangement_onArrangeGroups() {
        EchoToolHarness h = groupedHarness();

        JsonNode root = h.success("arrange-groups", "viewId", h.f.view.getId(), "arrangement", "GRID");

        assertEquals("grid", root.path("result").path("arrangement").asText());
    }

    @Test
    public void shouldAcceptAnUpperCaseGridArrangement_onLayoutFlatView() {
        EchoToolHarness h = harness();

        JsonNode root = h.success("layout-flat-view", "viewId", h.f.view.getId(), "arrangement", "GRID");

        assertEquals("grid", root.path("result").path("arrangement").asText());
    }

    @Test
    public void shouldAcceptAnUpperCaseGridArrangement_onOptimizeGroupOrder() {
        List<String> layout = optimizeGroupOrder("arrangement", "GRID");

        assertEquals(english(() -> optimizeGroupOrder("arrangement", "GRID")), layout);
    }

    @Test
    public void shouldAcceptAnUpperCaseGridGroupArrangement_onOptimizeGroupOrder() {
        List<String> layout = optimizeGroupOrder("groupArrangements", Map.of("g1", "GRID"));

        assertEquals(english(() -> optimizeGroupOrder("groupArrangements", Map.of("g1", "GRID"))), layout);
    }

    // ---- the refusal for a modelId that is not a string ---------------------------------------

    @Test
    public void shouldNameAnIntegerModelId_asOnEnglish() {
        String message = harness().message("get-views", "modelId", 7);

        assertEquals("modelId must be the id of an open model, as a non-empty string; got a integer", message);
    }

    // ---- the spacing tools' reason code ----------------------------------------------------

    @Test
    public void shouldSlugAReasonWithACapitalI_asOnEnglish() {
        String reason = "element: Invalid group layout";

        String code = SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false, reason);

        assertEquals("structural_no_change_element_invalid_group_layout", code);
        assertEquals(english(() -> SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false, reason)),
                code);
    }

    // ---- control: name search keeps the user's locale ----------------------------------------

    @Test
    public void shouldKeepTheTurkishFold_whenSearchingNames() {
        EchoToolHarness h = harness();
        h.f.element("identity");
        h.f.element("İstanbul");

        String upperId = h.success("search-elements", "query", "ID").toString();
        String istanbul = h.success("search-elements", "query", "istanbul").toString();

        assertFalse("under tr, \"ID\" folds to a dotless ıd and does not match identity: " + visible(upperId),
                upperId.contains("\"identity\""));
        assertTrue("under tr, İstanbul folds to istanbul and matches: " + visible(istanbul),
                istanbul.contains("stanbul\""));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private EchoToolHarness harness() {
        try {
            EchoToolHarness h = new EchoToolHarness("model-keyword-case-fold-" + harnesses.size());
            harnesses.add(h);
            return h;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The harness view plus two groups of two, joined across the groups. */
    private EchoToolHarness groupedHarness() {
        EchoToolHarness h = harness();
        IDiagramModelGroup g1 = h.f.group(h.f.view, "g1", 40, 300, 400, 300);
        IDiagramModelGroup g2 = h.f.group(h.f.view, "g2", 500, 300, 400, 300);
        IArchimateElement e1 = h.f.element("e1");
        IArchimateElement e3 = h.f.element("e3");
        IDiagramModelArchimateObject o1 = h.f.object(g1, "o1", e1, 10, 30, 120, 55);
        h.f.object(g1, "o2", h.f.element("e2"), 10, 130, 120, 55);
        IDiagramModelArchimateObject o3 = h.f.object(g2, "o3", e3, 10, 30, 120, 55);
        h.f.object(g2, "o4", h.f.element("e4"), 10, 130, 120, 55);
        h.f.archimateConnection("conn-13", h.f.relationship("rel-13", e1, e3), o1, o3);
        return h;
    }

    /** The layout {@code optimize-group-order} leaves, every object at every depth. */
    private List<String> optimizeGroupOrder(String key, Object value) {
        EchoToolHarness h = groupedHarness();
        h.success("optimize-group-order", "viewId", h.f.view.getId(), key, value);
        List<String> out = new ArrayList<>();
        collect(h.f.view, out);
        return out;
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

    private record LaidOut(String direction, List<String> layout) {
    }

    private LaidOut autoLayoutRight() {
        EchoToolHarness h = harness();
        JsonNode root = h.success("auto-layout-and-route", "viewId", h.f.view.getId(), "direction", "right");
        return new LaidOut(root.path("result").path("direction").asText(), boundsOf(h));
    }

    private static List<String> boundsOf(EchoToolHarness h) {
        List<String> out = new ArrayList<>();
        for (IDiagramModelObject o : h.f.view.getChildren()) {
            out.add(o.getId() + "@" + o.getBounds().getX() + "," + o.getBounds().getY() + ","
                    + o.getBounds().getWidth() + "x" + o.getBounds().getHeight());
        }
        return out;
    }

    private static void validate(String key, String value) {
        switch (key) {
            case "figureType" -> StylingHelper.validateFigureType(value);
            case "textAlignment" -> StylingHelper.validateTextAlignment(value);
            case "verticalTextAlignment" -> StylingHelper.validateVerticalTextAlignment(value);
            case "fontStyle" -> StylingHelper.validateFontStyle(value);
            case "lineStyle" -> StylingHelper.validateLineStyle(value);
            case "gradient" -> StylingHelper.validateGradient(value);
            case "borderType" -> StylingHelper.validateBorderType(value);
            default -> throw new IllegalArgumentException(key);
        }
    }

    /** The int the value maps to, or the exception's class when the mapper throws. */
    private static Object map(String key, String value) {
        try {
            return switch (key) {
                case "figureType" -> StylingHelper.mapFigureTypeToInt(value);
                case "textAlignment" -> StylingHelper.mapTextAlignmentToInt(value);
                case "verticalTextAlignment" -> StylingHelper.mapVerticalTextAlignmentToInt(value);
                case "fontStyle" -> StylingHelper.mapFontStyleToInt(value);
                case "lineStyle" -> StylingHelper.mapLineStyleToInt(value);
                case "gradient" -> StylingHelper.mapGradientToInt(value);
                case "borderType" -> StylingHelper.mapBorderTypeToInt(value);
                default -> throw new IllegalArgumentException(key);
            };
        } catch (IllegalArgumentException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String report(String key, String value) {
        return switch (key) {
            case "figureType" -> StylingHelper.computePostStylingFigureType(null, value);
            case "textAlignment" -> StylingHelper.computePostStylingTextAlignment(null, value);
            case "verticalTextAlignment" -> StylingHelper.computePostStylingVerticalTextAlignment(null, value);
            case "fontStyle" -> StylingHelper.computePostStylingFontStyle(null, value);
            case "lineStyle" -> StylingHelper.computePostStylingLineStyle(null, value);
            case "gradient" -> StylingHelper.computePostStylingGradient(null, value);
            case "borderType" -> StylingHelper.computePostStylingBorderType(null, value);
            default -> throw new IllegalArgumentException(key);
        };
    }

    /** {@code body} run with the default locale English, then set back to Turkish. */
    private static <T> T english(Callable<T> body) {
        Locale.setDefault(Locale.ENGLISH);
        try {
            return body.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Locale.setDefault(TURKISH);
        }
    }
}
