package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * {@code set-session-filter} reads {@code type}, {@code layer}, {@code fields} and {@code exclude} strictly: a
 * blank or non-string value, an {@code exclude} that is not an array, an empty one, or one holding an entry
 * that is not a non-blank string is refused naming the key, whole message pinned, and the session's filter
 * is left as it was. Each used to be read as absent, so the call either answered with the wrong refusal or
 * set the other keys and dropped this one in silence.
 */
public class SessionFilterArgumentReadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CommandRegistry registry;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        new SessionHandler(new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS),
                new ResponseFormatter(), registry).registerTools();
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return args;
    }

    private JsonNode call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow()
                .callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        try {
            JsonNode root = JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("isError",
                    Boolean.TRUE.equals(result.isError()));
            return root;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String filters() {
        return call("get-session-filters", null).path("result").toString();
    }

    /** Refused with {@code message} and INVALID_PARAMETER, and the session's filter unchanged. */
    private void refused(List<String> wrong, Map<String, Object> args, String message) {
        String before = filters();
        JsonNode reply = call("set-session-filter", args);
        JsonNode error = reply.path("error");
        if (!reply.path("isError").asBoolean()) {
            wrong.add(args + ": answered " + reply);
            return;
        }
        if (!"INVALID_PARAMETER".equals(error.path("code").asText())) {
            wrong.add(args + ": code " + error.path("code").asText());
        }
        if (!message.equals(error.path("message").asText())) {
            wrong.add(args + ": " + error.path("message").asText());
        }
        if (!before.equals(filters())) {
            wrong.add(args + ": the filter " + before + " became " + filters());
        }
    }

    @Test
    public void shouldRefuseAValueThatDoesNotRead_withTheFilterUnchanged() {
        assertFalse(call("set-session-filter", args("type", "ApplicationComponent", "fields", "minimal"))
                .path("isError").asBoolean());
        List<String> wrong = new ArrayList<>();
        refused(wrong, args("type", " "), "type must not be blank, got the string ' '. Nothing was changed.");
        refused(wrong, args("type", ""), "type must not be blank, got an empty string. Nothing was changed.");
        refused(wrong, args("type", 5), "type must be a string, got the integer 5. Nothing was changed.");
        refused(wrong, args("type", " ", "layer", "Business"),
                "type must not be blank, got the string ' '. Nothing was changed.");
        refused(wrong, args("layer", List.of("Business")),
                "layer must be a string, got an array. Nothing was changed.");
        refused(wrong, args("fields", true), "fields must be a string, got the boolean true. Nothing was changed.");
        refused(wrong, args("exclude", "documentation"),
                "exclude must be an array of strings, got the string 'documentation'. Nothing was changed.");
        refused(wrong, args("exclude", List.of()),
                "exclude must name at least one field, got an empty array. Nothing was changed.");
        refused(wrong, args("exclude", List.of("documentation", 5)),
                "exclude[1] must be a non-empty string, got the integer 5. Nothing was changed.");
        refused(wrong, args("exclude", List.of(" ")),
                "exclude[0] must be a non-empty string, got the string ' '. Nothing was changed.");
        refused(wrong, args("clear", true, "type", " "),
                "type must not be blank, got the string ' '. Nothing was changed.");
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldSuggestOmittingTheKey_notActingOnAll() {
        assertEquals("Pass exclude as an array of strings, or omit it.",
                call("set-session-filter", args("exclude", "documentation")).path("error")
                        .path("suggestedCorrection").asText());
        assertEquals("Name at least one field in exclude, or omit it.",
                call("set-session-filter", args("exclude", List.of())).path("error")
                        .path("suggestedCorrection").asText());
        assertEquals("Pass every entry of exclude as a non-empty string, or omit it.",
                call("set-session-filter", args("exclude", List.of(5))).path("error")
                        .path("suggestedCorrection").asText());
        assertEquals("Pass type as a non-blank string or omit it.",
                call("set-session-filter", args("type", " ")).path("error").path("suggestedCorrection").asText());
    }

    @Test
    public void shouldStillSetAndClearWithValuesThatRead() {
        assertFalse(call("set-session-filter", args("type", "BusinessActor")).path("isError").asBoolean());
        assertEquals("BusinessActor", call("get-session-filters", null).path("result").path("activeFilters")
                .path("type").asText());
        assertFalse(call("set-session-filter", args("exclude", List.of("documentation"))).path("isError")
                .asBoolean());
        assertEquals("[\"documentation\"]", call("get-session-filters", null).path("result")
                .path("activeFieldSelection").path("exclude").toString());
        assertFalse(call("set-session-filter", args("clear", true)).path("isError").asBoolean());
        JsonNode cleared = call("get-session-filters", null).path("result");
        assertEquals(cleared.toString(), "", cleared.path("activeFilters").path("type").asText());
        assertEquals(cleared.toString(), 0, cleared.path("activeFieldSelection").path("exclude").size());
    }
}
