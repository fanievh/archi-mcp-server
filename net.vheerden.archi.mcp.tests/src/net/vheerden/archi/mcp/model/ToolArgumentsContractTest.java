package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Every registered tool is held to the keys its served schema declares, {@code modelId}, and the
 * {@link ToolArguments#UNDECLARED_READ} allow-list, and each allow-list entry is still true: the tool is
 * registered, does not declare the key, and reads it.
 */
public class ToolArgumentsContractTest {

    private static final String UNKNOWN = "zzNotAnArgument";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("contract-model");
        // A second relationship between the two placed elements, not yet drawn, for add-connection-to-view.
        h.f.relationship("rel-ab2",
                (com.archimatetool.model.IArchimateConcept) com.archimatetool.model.util.ArchimateModelUtils
                        .getObjectByID(h.f.model, "el-a"),
                (com.archimatetool.model.IArchimateConcept) com.archimatetool.model.util.ArchimateModelUtils
                        .getObjectByID(h.f.model, "el-b"));
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private List<McpSchema.Tool> tools() {
        List<McpSchema.Tool> tools = new ArrayList<>();
        for (McpServerFeatures.SyncToolSpecification spec : h.registry().getToolSpecifications()) {
            tools.add(spec.tool());
        }
        return tools;
    }

    private McpSchema.Tool tool(String name) {
        return tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    private static List<String> declared(McpSchema.Tool tool) {
        McpSchema.JsonSchema schema = tool.inputSchema();
        return schema == null || schema.properties() == null ? List.of() : new ArrayList<>(schema.properties().keySet());
    }

    @Test
    public void everyRegisteredTool_refusesAnUnknownArgument_byName_beforeItRuns() {
        assertEquals("every tool the server registers", 74, tools().size());
        for (McpSchema.Tool tool : tools()) {
            Map<String, Object> args = new LinkedHashMap<>();
            List<String> required = tool.inputSchema() == null || tool.inputSchema().required() == null ? List.of()
                    : tool.inputSchema().required();
            for (String key : required) {
                // A required modelId is checked by the model scope first, so it must name the open model.
                args.put(key, key.equals(ToolArguments.MODEL_ID) ? "contract-model" : "x");
            }
            args.put(UNKNOWN, true);
            String accepts = tool.name() + " accepts " + String.join(", ", declared(tool)) + ".";
            JsonNode error = h.errorOf(tool.name(), args);
            assertEquals(tool.name(), "INVALID_PARAMETER", error.path("code").asText());
            assertEquals(tool.name(), tool.name() + " does not accept '" + UNKNOWN + "'. An argument the tool cannot "
                    + "read is refused rather than ignored, and nothing was changed. " + accepts,
                    error.path("message").asText());
        }
    }

    @Test
    public void everyDeclaredKey_andModelId_andTheAllowList_areAccepted() {
        // Through the registered call scope, so a drift between the served schema and the shape the scope
        // builds from it is caught too. One unknown key rides along, so the call is refused before any
        // handler runs (some reach the UI thread); a refusal naming only that key accepted all the others.
        for (McpSchema.Tool tool : tools()) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (String key : declared(tool)) {
                args.put(key, "x");
            }
            args.put(ToolArguments.MODEL_ID, "contract-model");
            for (String key : ToolArguments.UNDECLARED_READ.getOrDefault(tool.name(), Set.of())) {
                args.put(key, "x");
            }
            args.put(UNKNOWN, true);
            assertEquals(tool.name(), tool.name() + " does not accept '" + UNKNOWN + "'. An argument the tool cannot "
                    + "read is refused rather than ignored, and nothing was changed. " + tool.name() + " accepts "
                    + String.join(", ", declared(tool)) + ".", h.errorOf(tool.name(), args).path("message").asText());
        }
    }

    /** For each allow-list entry, a call the tool answers without that key; with it, the tool must answer differently. */
    private Map<String, Object> baseCall(String tool) {
        return switch (tool) {
            case "update-view-connection" -> EchoToolHarness.args("viewConnectionId", "conn-ab");
            case "add-connection-to-view" -> EchoToolHarness.args("viewId", h.f.view.getId(),
                    "relationshipId", "rel-ab2", "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b");
            case "auto-connect-view" -> EchoToolHarness.args("viewId", h.f.view.getId());
            default -> null;
        };
    }

    @Test
    public void everyAllowListEntry_namesARegisteredTool_thatReadsAKeyItDoesNotDeclare() throws Exception {
        assertFalse(ToolArguments.UNDECLARED_READ.isEmpty());
        for (Map.Entry<String, Set<String>> entry : ToolArguments.UNDECLARED_READ.entrySet()) {
            // A fresh model per entry: one tool's base call may draw what another's needs undrawn.
            tearDown();
            setUp();
            String name = entry.getKey();
            McpSchema.Tool tool = tool(name);
            assertNotNull("allow-listed tool is not registered: " + name, tool);
            for (String key : entry.getValue()) {
                assertFalse(name + " declares " + key + "; it needs no allow-list entry",
                        declared(tool).contains(key));
                Map<String, Object> base = baseCall(name);
                if (base == null) {
                    fail("give " + name + " a base call so the read of " + key + " can be proved");
                }
                Map<String, Object> withKey = new LinkedHashMap<>(base);
                withKey.put(key, "dashed");
                String answer = h.call(name, withKey).toString();
                assertFalse(name + " answered " + key + " as an unknown argument: " + answer,
                        answer.contains("does not accept"));
                assertTrue(name + " must read " + key + " (its answer names it): " + answer, answer.contains(key));
                assertFalse(name + " names " + key + " without being sent it, so naming it proves nothing",
                        h.call(name, base).toString().contains(key));
            }
        }
    }

    @Test
    public void everyBulkOperation_isHeldToAShape() {
        for (String op : BulkOperation.SUPPORTED_TOOLS_ORDERED) {
            assertTrue(op + " has neither a registered tool nor a known shape",
                    tool(op) != null || ToolArguments.knownShape(op) != null);
        }
    }

    @Test
    public void theUpdateViewConnectionShape_isItsServedSchema() {
        assertEquals(declared(tool("update-view-connection")),
                ToolArguments.knownShape("update-view-connection").declared());
    }
}
