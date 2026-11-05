package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.whyMalformed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Every tool as the server registers it, model scope included, over {@link ViewContentsFixture}'s
 * accessor, for the pins on how a caller's text is repeated back. Each call clears the session's cache
 * first, so no answer is a cached copy of another call's.
 *
 * <p>The model holds two application components on the fixture's view joined by an association
 * ({@code el-a}, {@code el-b}, {@code conn-ab}), a data object {@code do-1}, and an access relationship
 * {@code rel-acc} named {@code acc-link} from {@code el-a} to it.</p>
 */
final class EchoToolHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    final ViewContentsFixture f;
    private final CommandRegistry registry = new CommandRegistry();
    private final SessionManager sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);

    EchoToolHarness(String modelId) throws Exception {
        f = new ViewContentsFixture(modelId);
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), f.object(f.view, "vo-a", a, 40, 40, 120, 60),
                f.object(f.view, "vo-b", b, 400, 40, 120, 60));
        IArchimateElement data = f.factory.createDataObject();
        data.setId("do-1");
        data.setName("do-1");
        f.model.getFolder(FolderType.APPLICATION).getElements().add(data);
        IAccessRelationship access = f.factory.createAccessRelationship();
        access.setId("rel-acc");
        access.setName("acc-link");
        access.setSource(a);
        access.setTarget(data);
        f.model.getFolder(FolderType.RELATIONS).getElements().add(access);
        HandlerRegistrar.registerAll(f.accessor, new ResponseFormatter(), registry, sessions);
    }

    void dispose() {
        f.dispose();
    }

    /** The registry every tool is registered in, call scope included. */
    CommandRegistry registry() {
        return registry;
    }

    /** The decoded response of a tool call, with {@code isError} added from the call's result. */
    JsonNode call(String tool, Map<String, Object> args) {
        sessions.invalidateSessionCache(null);
        return callKeepingCache(tool, args);
    }

    /**
     * As {@link #call}, but the session's cache is left as the earlier calls made it, so this call can be
     * answered from an entry another call put there.
     */
    JsonNode callKeepingCache(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow()
                .callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        try {
            JsonNode root = JSON.readTree(text);
            ((ObjectNode) root).put("isError", Boolean.TRUE.equals(result.isError()));
            return root;
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return args;
    }

    JsonNode errorOf(String tool, Map<String, Object> args) {
        JsonNode root = call(tool, args);
        assertTrue(tool + " answered " + visible(root.toString()), root.path("isError").asBoolean());
        return root.path("error");
    }

    JsonNode servedError(String tool, Object... keyValues) {
        return errorOf(tool, args(keyValues));
    }

    String message(String tool, Object... keyValues) {
        return servedError(tool, keyValues).path("message").asText();
    }

    JsonNode success(String tool, Object... keyValues) {
        JsonNode root = call(tool, args(keyValues));
        assertTrue(tool + " refused " + visible(root.toString()), !root.path("isError").asBoolean());
        return root;
    }

    /** The one failure row a bulk call through the accessor answers. */
    BulkOperationFailure bulkRow(String tool, Map<String, Object> params, String as) {
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION,
                    List.of(new BulkOperation(tool, new LinkedHashMap<>(params), as)), null, false);
            fail("expected a refusal, got allSucceeded=" + result.allSucceeded());
            return null;
        } catch (BulkValidationException e) {
            List<BulkOperationFailure> rows = e.getFailures();
            assertEquals("one failure row: " + rows, 1, rows.size());
            return rows.get(0);
        }
    }

    /**
     * Null when the text keeps the site's own words, holds nothing malformed, marks a cut value as partial,
     * and repeats exactly what it should: read back through its escapes, the quoted text is the whole value
     * when it is at most the bound, and otherwise its first {@link CallerText#MAX_ECHOED} units, one fewer
     * when the last of those starts a surrogate pair.
     */
    static String whyBroken(String prefix, String suffix, String value, String text) {
        String why = whyMalformed(text);
        if (why != null) {
            return why;
        }
        boolean cut = value.length() > CallerText.MAX_ECHOED;
        String marker = cut ? " (the start of " + value.length() + " characters)" : "";
        String close = "'" + marker + suffix;
        if (!text.startsWith(prefix + "'") || !text.endsWith(close)
                || text.length() < prefix.length() + 1 + close.length()) {
            return cut ? "surrounding text changed, or a cut value not marked as partial" : "surrounding text changed";
        }
        String echoed = unescape(text.substring(prefix.length() + 1, text.length() - close.length()));
        int kept = !cut ? value.length()
                : Character.isHighSurrogate(value.charAt(CallerText.MAX_ECHOED - 1))
                        ? CallerText.MAX_ECHOED - 1 : CallerText.MAX_ECHOED;
        return echoed.equals(value.substring(0, kept)) ? null
                : "repeats " + visible(echoed) + " instead of the first " + kept + " units";
    }

    /** The caller's text back from its escapes: a backslash before a quote or backslash, n, r, t or uXXXX. */
    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** The shipped source's files, each as its lines with comment lines dropped, keyed by its package path. */
    static Map<String, List<String>> shippedCode() throws IOException {
        Path root = locateRepoDirectory("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp");
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> code = new ArrayList<>();
                for (String line : Files.readAllLines(file)) {
                    String t = line.strip();
                    if (!(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))) {
                        code.add(line);
                    }
                }
                out.put(root.relativize(file).toString().replace('\\', '/'), code);
            }
        }
        assertTrue("scanned only " + out.size() + " files under " + root, out.size() > 100);
        return out;
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
