package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * The {@code change-relationship-type} tool's handler: its schema and description, how it reads
 * the entries, and the envelope it answers with. The accessor is a stub; what a type change does is
 * pinned in the model package.
 */
public class ElementUpdateHandlerChangeRelationshipTypeTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CommandRegistry registry = new CommandRegistry();
    private StubAccessor accessor;
    private ElementUpdateHandler handler;

    @Before
    public void setUp() {
        accessor = new StubAccessor();
        handler = new ElementUpdateHandler(accessor, new ResponseFormatter(), registry, null);
        handler.registerTools();
    }

    @Test
    public void shouldDeclareOneRequiredArrayOfRelationshipIdAndType() {
        McpSchema.Tool tool = tool("change-relationship-type");
        assertEquals(List.of("changes"), tool.inputSchema().required());
        assertEquals(List.of("changes"), new ArrayList<>(tool.inputSchema().properties().keySet()));
        Map<String, Object> changes = property(tool, "changes");
        assertEquals("array", changes.get("type"));
        assertEquals(1, changes.get("minItems"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) changes.get("items");
        assertEquals(List.of("relationshipId", "type"), item.get("required"));
        assertEquals(false, item.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) item.get("properties");
        assertNull("the type is a name, as create-relationship takes it, not an enum", props.get("type").get("enum"));
    }

    @Test
    public void shouldDescribeWhatIsKeptDroppedResetAndRefused() {
        String d = tool("change-relationship-type").description();
        assertTrue(d.startsWith("[Mutation] "));
        for (String part : List.of("keeps its id", "bendpoints", "place in its folder", "stays attached",
                "attributesDropped", "specializationDropped", "attributes: an Access is 'write'",
                "at a Junction unless the call lists every one there with one type",
                "Archi's own Change Type judges each relationship alone and never changes one at a Junction",
                "every one is named", "Refused inside a batch", "one undo step",
                "Respects approval mode", "idMap")) {
            assertTrue("the description must say: " + part, d.contains(part));
        }
        assertTrue(d, d.endsWith("Related: update-relationship (set the new type's attributes), get-relationships, "
                + "change-element-type"));
    }

    @Test
    public void shouldLeaveUpdateRelationshipsPromiseThatTheTypeIsImmutable() {
        assertFalse(tool("update-relationship").inputSchema().properties().containsKey("type"));
    }

    @Test
    public void shouldPassTheEntries_asSent() throws Exception {
        Map<String, Object> envelope = call(Map.of("changes", List.of(
                Map.of("relationshipId", "r1", "type", "TriggeringRelationship"),
                Map.of("relationshipId", "r2", "type", "ServingRelationship"))));

        assertNotNull(envelope.get("result"));
        assertEquals(List.of(new RelationshipTypeChangeRequest("r1", "TriggeringRelationship"),
                new RelationshipTypeChangeRequest("r2", "ServingRelationship")), accessor.changes);
    }

    @Test
    public void shouldPassAMissingFieldOrAMissingArray_asNull_forTheAccessorToRefuse() throws Exception {
        call(Map.of("changes", List.of(Map.of("type", "TriggeringRelationship"))));
        assertEquals(Arrays.asList(new RelationshipTypeChangeRequest(null, "TriggeringRelationship")),
                accessor.changes);
        accessor.changes = List.of();
        call(new HashMap<>());
        assertNull(accessor.changes);
    }

    @Test
    public void shouldRefuseEntriesOfTheWrongShape_beforeTheAccessorRuns() throws Exception {
        assertInvalid(Map.of("changes", "r1"), "changes must be an array");
        assertInvalid(Map.of("changes", List.of("r1")), "changes[0] must be an object");
        assertInvalid(Map.of("changes", List.of(Map.of("relationshipId", 7, "type", "FlowRelationship"))),
                "changes[0].relationshipId must be a string");
        assertInvalid(Map.of("changes", List.of(Map.of("relationshipId", "r1", "type", List.of("x")))),
                "changes[0].type must be a string");
        assertInvalid(Map.of("changes", List.of(Map.of("relationshipId", "r1", "newType", "FlowRelationship"))),
                "changes[0] has an unknown key 'newType'");
        assertInvalid(Map.of("changes", List.of(Map.of("elementId", "r1", "type", "FlowRelationship"))),
                "changes[0] has an unknown key 'elementId'");
        assertEquals("the accessor never ran", 0, accessor.calls);
    }

    @Test
    public void shouldAnswerACallerErrorFromTheAccessor_asThatError() throws Exception {
        accessor.refusal = new ModelAccessException("The new types would leave 1 relationship ...",
                ErrorCode.RELATIONSHIP_NOT_ALLOWED, null, "Leave those relationships out", null);
        Map<String, Object> envelope = call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "FlowRelationship"))));
        assertEquals("RELATIONSHIP_NOT_ALLOWED", error(envelope).get("code"));
    }

    @Test
    public void shouldNestThePreviewBesideTheProposal_inApprovalMode() throws Exception {
        accessor.proposal = new ProposalContext("p-1", "Change the type of 1 relationship", Instant.now());
        Map<String, Object> envelope = call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "TriggeringRelationship"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull(result.get("proposal"));
        assertNotNull(result.get("preview"));
        assertNull("not at the top level, where it would read as state the model holds", result.get("idMap"));
        assertTrue(String.valueOf(envelope.get("nextSteps")).contains("list-pending-approvals"));
    }

    @Test
    public void shouldPointAtGetRelationships_andAtTheNewAttribute_onlyWhenTheNewTypeHasOne() throws Exception {
        accessor.toType = "AccessRelationship";
        String steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "AccessRelationship")))).get("nextSteps"));
        assertTrue(steps, steps.contains("get-relationships"));
        assertTrue(steps, steps.contains("set it with update-relationship"));
        accessor.toType = "InfluenceRelationship";
        steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "InfluenceRelationship")))).get("nextSteps"));
        assertTrue("an Influence has an unset strength to set: " + steps,
                steps.contains("set it with update-relationship"));
        accessor.toType = "TriggeringRelationship";
        steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "TriggeringRelationship")))).get("nextSteps"));
        assertFalse(steps, steps.contains("update-relationship ("));
    }

    @Test
    public void shouldPointAtTheDroppedSpecialization_inItsNextSteps() throws Exception {
        accessor.specializations = 1;
        String steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "TriggeringRelationship")))).get("nextSteps"));
        assertTrue(steps, steps.contains("specializationDropped"));
        accessor.specializations = 0;
        steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("relationshipId", "r1", "type", "TriggeringRelationship")))).get("nextSteps"));
        assertFalse(steps, steps.contains("specializationDropped"));
    }

    // ---- helpers ----

    private McpSchema.Tool tool(String name) {
        return registry.getToolSpecifications().stream().map(s -> s.tool())
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(McpSchema.Tool tool, String name) {
        return (Map<String, Object>) tool.inputSchema().properties().get(name);
    }

    private Map<String, Object> call(Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = handler.handleChangeRelationshipType(null,
                McpSchema.CallToolRequest.builder().name("change-relationship-type").arguments(args).build());
        return json.readValue(((McpSchema.TextContent) result.content().get(0)).text(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("error");
    }

    private void assertInvalid(Map<String, Object> args, String messagePart) throws Exception {
        Map<String, Object> err = error(call(args));
        assertNotNull("expected an error for " + args, err);
        assertEquals("INVALID_PARAMETER", err.get("code"));
        assertTrue(err.get("message") + " should contain " + messagePart,
                String.valueOf(err.get("message")).contains(messagePart));
    }

    private static final class StubAccessor extends BaseTestAccessor {
        List<RelationshipTypeChangeRequest> changes;
        int calls;
        ModelAccessException refusal;
        ProposalContext proposal;
        int specializations;
        String toType = "TriggeringRelationship";

        StubAccessor() {
            super(true);
        }

        @Override
        public MutationResult<ChangeRelationshipTypeResultDto> changeRelationshipType(String sessionId,
                List<RelationshipTypeChangeRequest> changes) {
            this.changes = changes;
            calls++;
            if (refusal != null) {
                throw refusal;
            }
            ChangeRelationshipTypeResultDto.Change change = new ChangeRelationshipTypeResultDto.Change("r1", "r1",
                    "sends", "FlowRelationship", toType, "x", "y", 1, 0, "folder", null,
                    "AccessRelationship".equals(toType) ? new RelationshipSemanticAttributes("write", null, null)
                            : null,
                    specializations > 0 ? new SpecializationDropped("Strong", "FlowRelationship") : null);
            ChangeRelationshipTypeResultDto dto = new ChangeRelationshipTypeResultDto(List.of(change),
                    Map.of("r1", "r1"), new ChangeRelationshipTypeResultDto.Totals(1, 1, 0, 0, specializations));
            return new MutationResult<>(dto, null, proposal);
        }
    }
}
