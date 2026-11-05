package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
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
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;

/**
 * The {@code change-element-type} tool's handler: its schema and description, how it reads the
 * entries, and the envelope it answers with. The accessor is a stub; what a type change does is
 * pinned in the model package.
 */
public class ElementUpdateHandlerChangeElementTypeTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CommandRegistry registry = new CommandRegistry();
    private StubChangeTypeAccessor accessor;
    private ElementUpdateHandler handler;

    @Before
    public void setUp() {
        accessor = new StubChangeTypeAccessor();
        handler = new ElementUpdateHandler(accessor, new ResponseFormatter(), registry, null);
        handler.registerTools();
    }

    @Test
    public void shouldDeclareOneRequiredArrayOfElementIdAndType() {
        McpSchema.Tool tool = tool("change-element-type");
        assertEquals(List.of("changes"), tool.inputSchema().required());
        assertEquals(List.of("changes", "invalidRelationships"),
                new ArrayList<>(tool.inputSchema().properties().keySet()));
        Map<String, Object> changes = property(tool, "changes");
        assertEquals("array", changes.get("type"));
        assertEquals(1, changes.get("minItems"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) changes.get("items");
        assertEquals(List.of("elementId", "type"), item.get("required"));
        assertEquals(false, item.get("additionalProperties"));
    }

    @Test
    public void shouldDescribeWhatIsKeptDroppedResetAndRefused() {
        String d = tool("change-element-type").description();
        assertTrue(d.startsWith("[Mutation] "));
        for (String part : List.of("keeps its id", "specializationDropped", "figureTypesReset",
                "a relationship the new types would make invalid", "Junction", "Refused inside a batch",
                "one undo step", "Respects approval mode", "idMap",
                "Related: merge-elements", "update-element", "get-relationships")) {
            assertTrue("the description must say: " + part, d.contains(part));
        }
    }

    @Test
    public void shouldDeclareInvalidRelationships_asAnOptionalChoiceOfTwo() {
        Map<String, Object> choice = property(tool("change-element-type"), "invalidRelationships");
        assertEquals("string", choice.get("type"));
        assertEquals(List.of("refuse", "to-association"), choice.get("enum"));
        String d = String.valueOf(choice.get("description"));
        for (String part : List.of("'refuse' (default)", "'to-association'", "keeps its id", "attributesDropped",
                "specializationDropped", "documentation note")) {
            assertTrue("the property must say: " + part, d.contains(part));
        }
    }

    @Test
    public void shouldDescribeTheConversion_andWhatItDoesNotCheck() {
        String d = tool("change-element-type").description();
        for (String part : List.of("invalidRelationships", "to-association", "relationshipsConverted",
                "junction")) {
            assertTrue("the description must say: " + part, d.contains(part));
        }
    }

    @Test
    public void shouldPassInvalidRelationships_asSent_orNullWhenAbsent() throws Exception {
        call(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node")),
                "invalidRelationships", "to-association"));
        assertEquals("to-association", accessor.invalidRelationships);
        call(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node")),
                "invalidRelationships", "sideways"));
        assertEquals("an unknown value is the accessor's to refuse", "sideways", accessor.invalidRelationships);
        call(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node"))));
        assertNull(accessor.invalidRelationships);
    }

    @Test
    public void shouldRefuseInvalidRelationshipsOfTheWrongKind_namingBothValues() throws Exception {
        accessor.calls = 0;
        for (Object value : List.of(true, 1, List.of("refuse"))) {
            assertInvalid(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node")),
                    "invalidRelationships", value), "invalidRelationships must be a string: refuse or to-association");
        }
        assertTrue("the accessor never ran", accessor.calls == 0);
    }

    @Test
    public void shouldPointAtTheNewAssociations_inItsNextSteps_whenItConverted() throws Exception {
        accessor.converted = 2;
        String steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("elementId", "a", "type", "Node")))).get("nextSteps"));
        assertTrue(steps, steps.contains("Review relationshipsConverted: get-relationships shows the new Associations"));
        accessor.converted = 0;
        steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("elementId", "a", "type", "Node")))).get("nextSteps"));
        assertTrue(steps, !steps.contains("relationshipsConverted"));
    }

    @Test
    public void shouldNameThisTool_inTheMergeDescriptionsCrossTypeRefusal() {
        assertTrue(tool("merge-elements").description().contains("change its type first with change-element-type"));
    }

    @Test
    public void shouldPassTheEntries_asSent() throws Exception {
        Map<String, Object> envelope = call(Map.of("changes", List.of(
                Map.of("elementId", "a", "type", "BusinessRole"),
                Map.of("elementId", "b", "type", "Node"))));

        assertNotNull(envelope.get("result"));
        assertEquals(List.of(new ElementTypeChangeRequest("a", "BusinessRole"),
                new ElementTypeChangeRequest("b", "Node")), accessor.changes);
    }

    @Test
    public void shouldPassAMissingFieldOrAMissingArray_asNull_forTheAccessorToRefuse() throws Exception {
        call(Map.of("changes", List.of(Map.of("type", "Node"))));
        assertEquals(Arrays.asList(new ElementTypeChangeRequest(null, "Node")), accessor.changes);
        accessor.changes = List.of();
        call(new HashMap<>());
        assertNull(accessor.changes);
    }

    @Test
    public void shouldRefuseEntriesOfTheWrongShape_beforeTheAccessorRuns() throws Exception {
        assertInvalid(Map.of("changes", "a"), "changes must be an array");
        assertInvalid(Map.of("changes", List.of("a")), "changes[0] must be an object");
        assertInvalid(Map.of("changes", List.of(Map.of("elementId", 7, "type", "Node"))),
                "changes[0].elementId must be a string");
        assertInvalid(Map.of("changes", List.of(Map.of("elementId", "a", "type", List.of("Node")))),
                "changes[0].type must be a string");
        assertInvalid(Map.of("changes", List.of(Map.of("elementId", "a", "newType", "Node"))),
                "changes[0] has an unknown key 'newType'");
        assertTrue("the accessor never ran", accessor.calls == 0);
    }

    @Test
    public void shouldAnswerACallerErrorFromTheAccessor_asThatError() throws Exception {
        accessor.refusal = new ModelAccessException("The new types would leave 1 relationship ...",
                ErrorCode.RELATIONSHIP_NOT_ALLOWED, null, "Delete those relationships first", null);
        Map<String, Object> envelope = call(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node"))));
        assertEquals("RELATIONSHIP_NOT_ALLOWED", error(envelope).get("code"));
    }

    @Test
    public void shouldNestThePreviewBesideTheProposal_inApprovalMode() throws Exception {
        accessor.proposal = new ProposalContext("p-1", "Change the type of 1 element", Instant.now());
        Map<String, Object> envelope = call(Map.of("changes", List.of(Map.of("elementId", "a", "type", "Node"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull(result.get("proposal"));
        assertNotNull(result.get("preview"));
        assertNull("not at the top level, where it would read as state the model holds", result.get("idMap"));
        assertTrue(String.valueOf(envelope.get("nextSteps")).contains("list-pending-approvals"));
    }

    @Test
    public void shouldPointAtTheMergeAndTheDroppedSpecialization_inItsNextSteps() throws Exception {
        accessor.specializations = 1;
        String steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("elementId", "a", "type", "Node")))).get("nextSteps"));
        assertTrue(steps, steps.contains("get-element with id 'a'"));
        assertTrue(steps, steps.contains("merge-elements with the ids in idMap"));
        assertTrue(steps, steps.contains("specializationDropped"));
        accessor.specializations = 0;
        steps = String.valueOf(call(Map.of("changes",
                List.of(Map.of("elementId", "a", "type", "Node")))).get("nextSteps"));
        assertTrue(steps, !steps.contains("specializationDropped"));
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
        McpSchema.CallToolResult result = handler.handleChangeElementType(null,
                McpSchema.CallToolRequest.builder().name("change-element-type").arguments(args).build());
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

    private static final class StubChangeTypeAccessor extends BaseTestAccessor {
        List<ElementTypeChangeRequest> changes;
        int calls;
        ModelAccessException refusal;
        ProposalContext proposal;
        int specializations;
        int converted;
        String invalidRelationships;

        StubChangeTypeAccessor() {
            super(true);
        }

        @Override
        public MutationResult<ChangeElementTypeResultDto> changeElementType(String sessionId,
                List<ElementTypeChangeRequest> changes, String invalidRelationships) {
            this.changes = changes;
            this.invalidRelationships = invalidRelationships;
            calls++;
            if (refusal != null) {
                throw refusal;
            }
            ChangeElementTypeResultDto.Change change = new ChangeElementTypeResultDto.Change("a", "a", "Customer",
                    "BusinessActor", "Node", 0, 0, "folder", true, null,
                    specializations > 0 ? new ChangeElementTypeResultDto.SpecializationDropped("VIP", "BusinessActor")
                            : null, null);
            ChangeElementTypeResultDto dto = new ChangeElementTypeResultDto(List.of(change), Map.of("a", "a"),
                    new ChangeElementTypeResultDto.Totals(1, 0, 0, 0, specializations, 1, converted));
            return new MutationResult<>(dto, null, proposal);
        }
    }
}
