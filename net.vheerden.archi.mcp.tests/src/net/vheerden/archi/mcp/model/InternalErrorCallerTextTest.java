package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import com.archimatetool.model.FolderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins how an internal error repeats the id or name a caller sent. Such an error is answered to the
 * client and logged at ERROR, with its message as the log line, so the caller's text is bounded and
 * escaped there as in every refusal: one log line whatever the caller sent, and an ordinary id reads as
 * before. The failures are injected with an element whose name cannot be read, so the server's own
 * catch answers them.
 */
public class InternalErrorCallerTextTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A duplicate of the unreadable element's type, for the merge site to fold into it. */
    private static final String MERGE_SOURCE = "merge-source";

    /** Throws on reading its name while armed, which every one of the pinned operations does. */
    static final class UnreadableElement extends com.archimatetool.model.impl.ApplicationComponent {
        static volatile boolean armed;

        @Override
        public String getName() {
            if (armed) {
                throw new IllegalStateException("name unreadable");
            }
            return super.getName();
        }
    }

    /**
     * The same, for a relationship: throws on reading its name or its source end while the element
     * class is armed. The end is read before Archi's relationship rule, which cannot load in this
     * lane, so the failure is the server's own catch here too.
     */
    static final class UnreadableRelationship extends com.archimatetool.model.impl.FlowRelationship {
        @Override
        public String getName() {
            if (UnreadableElement.armed) {
                throw new IllegalStateException("name unreadable");
            }
            return super.getName();
        }

        @Override
        public com.archimatetool.model.IArchimateConcept getSource() {
            if (UnreadableElement.armed) {
                throw new IllegalStateException("source unreadable");
            }
            return super.getSource();
        }
    }

    private ViewContentsFixture f;
    private final CommandRegistry registry = new CommandRegistry();
    private SessionManager sessions;

    /** One site: how it is reached for an element id, and the message it answers. */
    private record Site(String name, String prefix, String suffix, BiFunction<InternalErrorCallerTextTest, String,
            String> reader) {}

    private static final List<Site> SITES = List.of(
            new Site("update-element", "Error updating element with ID ", "",
                    (t, id) -> t.internal("update-element", "id", id, "documentation", "d")),
            new Site("delete-element", "Error deleting element ", "",
                    (t, id) -> t.internal("delete-element", "elementId", id)),
            new Site("add-to-view", "Error adding element ", " to view 'view-source'",
                    (t, id) -> t.internal("add-to-view", "viewId", "view-source", "elementId", id)),
            new Site("merge-elements", "Error merging elements into ", "",
                    (t, id) -> t.internal("merge-elements", "merges",
                            List.of(Map.of("targetId", id, "sourceIds", List.of(MERGE_SOURCE))))),
            new Site("change-element-type", "Error changing the type of ", "",
                    (t, id) -> t.internal("change-element-type", "changes",
                            List.of(Map.of("elementId", id, "type", "ApplicationCollaboration")))),
            new Site("change-relationship-type", "Error changing the relationship type of ", "",
                    (t, id) -> t.asRelationship(id, () -> t.internal("change-relationship-type", "changes",
                            List.of(Map.of("relationshipId", id, "type", "TriggeringRelationship"))))));

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-internal-error-caller-text");
        sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(f.accessor, new ResponseFormatter(), registry, sessions);
        f.element(MERGE_SOURCE);
    }

    @After
    public void tearDown() {
        UnreadableElement.armed = false;
        f.dispose();
    }

    private void unreadable(String id) {
        UnreadableElement el = new UnreadableElement();
        el.setId(id);
        el.setName("unreadable");
        f.model.getFolder(FolderType.APPLICATION).getElements().add(el);
    }

    @Test
    public void shouldRepeatAnOrdinaryIdAsBefore() {
        unreadable("ghost-1");
        UnreadableElement.armed = true;
        assertEquals("Error updating element with ID 'ghost-1'", SITES.get(0).reader().apply(this, "ghost-1"));
        assertEquals("Error deleting element 'ghost-1'", SITES.get(1).reader().apply(this, "ghost-1"));
        assertEquals("Error adding element 'ghost-1' to view 'view-source'", SITES.get(2).reader().apply(this, "ghost-1"));
    }

    @Test
    public void shouldEscapeTheId_inTheClientMessage() {
        unreadable("p\nq");
        UnreadableElement.armed = true;
        assertEquals("Error updating element with ID 'p\\nq'", SITES.get(0).reader().apply(this, "p\nq"));
        assertEquals("Error deleting element 'p\\nq'", SITES.get(1).reader().apply(this, "p\nq"));
        assertEquals("Error adding element 'p\\nq' to view 'view-source'", SITES.get(2).reader().apply(this, "p\nq"));
    }

    @Test
    public void shouldLogOneErrorLine_whateverTheIdHolds() {
        String id = "p\nq\u2028r";
        unreadable(id);
        UnreadableElement.armed = true;
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("Error updating element with ID 'p\\nq\\u2028r'",
                () -> f.accessor.updateElement(ViewContentsFixture.SESSION, id, null, "d", null, null));
        calls.put("Error deleting element 'p\\nq\\u2028r'",
                () -> f.accessor.deleteElement(ViewContentsFixture.SESSION, id));
        calls.put("Error adding element 'p\\nq\\u2028r' to view 'view-source'",
                () -> f.accessor.addToView(ViewContentsFixture.SESSION, "view-source", id, null, null, null, null,
                        false, null, null, null));

        List<String> lines = new ArrayList<>();
        Logger log = (Logger) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("error") && args != null && args.length == 3) {
                        lines.add(((String) args[0]).replace("{}", String.valueOf(args[1])));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, Runnable> call : calls.entrySet()) {
            try {
                call.getValue().run();
                fail("the unreadable element was read: " + call.getKey());
            } catch (ModelAccessException e) {
                assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
                ToolErrorLog.log(log, e);
            }
            expected.add("Tool failed with INTERNAL_ERROR: " + call.getKey());
        }

        assertEquals(expected, lines);
    }

    @Test
    public void everyInjectedInternalError_shouldRepeatTheCallersStart_wellFormed() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String value : NASTY) {
            if (value.isBlank()) {
                continue; // a blank id is refused before the model is read
            }
            unreadable(value);
            UnreadableElement.armed = true;
            for (Site site : SITES) {
                String msg = site.reader().apply(this, value);
                checked++;
                String why = EchoToolHarness.whyBroken(site.prefix(), site.suffix(), value, msg);
                if (why != null) {
                    broken.add(site.name() + ", value " + visible(value) + ": " + why + " in " + visible(msg));
                }
            }
            UnreadableElement.armed = false;
        }
        assertEquals(NASTY.stream().filter(v -> !v.isBlank()).count() * SITES.size(), checked);
        assertTrue(broken.size() + " malformed internal errors:\n" + String.join("\n", broken), broken.isEmpty());
    }

    @Test
    public void shouldQuoteNoCallersValueByHand_inAnInternalError() throws IOException {
        List<String> impl = EchoToolHarness.shippedCode().get("model/ArchiModelAccessorImpl.java");
        List<Pattern> byHand = List.of(
                Pattern.compile("\"(Error|Cannot access)[^\"]*'\" *\\+"),
                Pattern.compile("\"(Error|Cannot access)[^\"]*'\"\\s*$"),
                Pattern.compile("\\+ \\([A-Za-z]+ != null \\? [A-Za-z]+ : \"<null>\"\\)"),
                Pattern.compile("\"' to view '\" *\\+"));
        List<String> found = new ArrayList<>();
        int routed = 0;
        for (String line : impl) {
            for (Pattern p : byHand) {
                if (p.matcher(line).find()) {
                    found.add(line.strip());
                }
            }
            if (line.contains("\"Error") && line.contains("CallerText.quoted(")
                    || line.contains("? CallerText.quoted(") && line.contains("\"'<null>'\"")) {
                routed++;
            }
        }
        assertTrue(found.size() + " internal errors quote a value by hand:\n" + String.join("\n", found),
                found.isEmpty());
        assertTrue("found only " + routed + " routed lines", routed >= 55);
    }

    /**
     * Runs {@code call} with the unreadable element of this id stood in for by an unreadable
     * relationship of the same id, so a site that takes relationship ids is reached the same way.
     */
    private String asRelationship(String id, java.util.function.Supplier<String> call) {
        com.archimatetool.model.IArchimateElement element = (com.archimatetool.model.IArchimateElement)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(f.model, id);
        com.archimatetool.model.IFolder folder = (com.archimatetool.model.IFolder) element.eContainer();
        com.archimatetool.model.IArchimateElement end = (com.archimatetool.model.IArchimateElement)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(f.model, MERGE_SOURCE);
        UnreadableRelationship relationship = new UnreadableRelationship();
        relationship.setId(id);
        relationship.connect(end, end);
        int index = folder.getElements().indexOf(element);
        folder.getElements().remove(element);
        f.model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        try {
            return call.get();
        } finally {
            f.model.getFolder(FolderType.RELATIONS).getElements().remove(relationship);
            relationship.disconnect();
            folder.getElements().add(index, element);
        }
    }

    /** The decoded {@code error.message} of an internal error answered to the client. */
    private String internal(String tool, Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        sessions.invalidateSessionCache(null);
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow()
                .callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        try {
            JsonNode error = JSON.readTree(text).path("error");
            assertEquals(text, ErrorCode.INTERNAL_ERROR.name(), error.path("code").asText());
            return error.path("message").asText();
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }
}
