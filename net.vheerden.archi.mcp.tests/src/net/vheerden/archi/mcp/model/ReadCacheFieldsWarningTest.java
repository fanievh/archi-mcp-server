package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A read tool's answer to an invalid {@code fields} preset carries a warning in {@code _meta}, and the read
 * cache stores the answer with it. Each warning must stay with the call that earned it: an invalid preset
 * followed by none must not replay the warning, and none followed by an invalid preset must not lose it.
 *
 * <p>Each sequence runs through the registered tools with the cache kept between its calls
 * ({@link EchoToolHarness#callKeepingCache}): the echo pins clear the cache before every call, which is why
 * they could not see this. The repeat of each call is asserted to be a cache hit, so the pin proves the
 * answers were cached at all.</p>
 */
public class ReadCacheFieldsWarningTest {

    private static final String WARNING = "Invalid fields preset 'bogus', using 'standard'. "
            + "Valid presets: minimal, standard, full";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("model-read-cache-fields-warning");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    /** The seven reads that cache, each with the arguments that make it answer. */
    private static Map<String, Map<String, Object>> cachingReads() {
        Map<String, Map<String, Object>> reads = new LinkedHashMap<>();
        reads.put("search-elements", args("tool", "search-elements", "query", "el"));
        reads.put("search-relationships", args("tool", "search-relationships", "query", "acc"));
        reads.put("get-views", args("tool", "get-views"));
        reads.put("get-view-contents", args("tool", "get-view-contents", "viewId", "view-source"));
        reads.put("get-relationships", args("tool", "get-relationships", "elementId", "el-a"));
        reads.put("get-relationships traverse",
                args("tool", "get-relationships", "elementId", "el-a", "traverse", true));
        reads.put("get-element", args("tool", "get-element", "id", "el-a"));
        return reads;
    }

    @Test
    public void shouldNotReplayAnInvalidPresetsWarning_toALaterCallThatSentNone() {
        List<String> wrong = new ArrayList<>();
        cachingReads().forEach((site, base) -> {
            h.call(tool(base), with(base, "bogus"));
            expect(wrong, site + ", none after bogus", h.callKeepingCache(tool(base), with(base, null)), null, false);
            expect(wrong, site + ", none repeated", h.callKeepingCache(tool(base), with(base, null)), null, true);
            expect(wrong, site + ", bogus repeated", h.callKeepingCache(tool(base), with(base, "bogus")), WARNING,
                    true);
        });
        assertEquals("each answer carries its own call's warning", List.of(), wrong);
    }

    @Test
    public void shouldWarnAnInvalidPreset_afterACallThatSentNone() {
        List<String> wrong = new ArrayList<>();
        cachingReads().forEach((site, base) -> {
            h.call(tool(base), with(base, null));
            expect(wrong, site + ", bogus after none", h.callKeepingCache(tool(base), with(base, "bogus")), WARNING, false);
        });
        assertEquals("each answer carries its own call's warning", List.of(), wrong);
    }

    /** The reads that warn without caching answer the same way, so they cannot start replaying a warning. */
    @Test
    public void shouldKeepEachWarningWithItsCall_onTheReadsThatDoNotCache() {
        Map<String, Map<String, Object>> reads = new LinkedHashMap<>();
        reads.put("get-element batch", args("tool", "get-element", "ids", List.of("el-a", "el-b")));
        reads.put("get-folders", args("tool", "get-folders"));
        reads.put("get-folder-tree", args("tool", "get-folder-tree"));
        List<String> wrong = new ArrayList<>();
        reads.forEach((site, base) -> {
            h.call(tool(base), with(base, "bogus"));
            expect(wrong, site + ", none after bogus", h.callKeepingCache(tool(base), with(base, null)), null);
            expect(wrong, site + ", bogus after none", h.callKeepingCache(tool(base), with(base, "bogus")), WARNING);
        });
        assertEquals("each answer carries its own call's warning", List.of(), wrong);
    }

    private static String tool(Map<String, Object> base) {
        return (String) base.get("tool");
    }

    private static Map<String, Object> with(Map<String, Object> base, String fields) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.remove("tool");
        if (fields != null) {
            out.put("fields", fields);
        }
        return out;
    }

    private static void expect(List<String> wrong, String site, JsonNode root, String warning) {
        expect(wrong, site, root, warning, null);
    }

    private static void expect(List<String> wrong, String site, JsonNode root, String warning, Boolean hit) {
        if (root.path("isError").asBoolean()) {
            wrong.add(site + ": refused " + root.path("error").path("message").asText());
            return;
        }
        JsonNode meta = root.path("_meta");
        String got = meta.path("warning").isMissingNode() ? null : meta.path("warning").asText();
        if (warning == null ? got != null : !warning.equals(got)) {
            wrong.add(site + ": warning " + got);
        }
        if (hit != null && hit != meta.path("cacheHit").asBoolean(false)) {
            wrong.add(site + ": cacheHit " + meta.path("cacheHit"));
        }
    }
}
