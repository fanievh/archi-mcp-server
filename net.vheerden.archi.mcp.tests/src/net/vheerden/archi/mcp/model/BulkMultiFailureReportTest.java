package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A bulk-mutate call that fails pre-validation reports every operation that failed, not the first
 * one and then silence.
 *
 * <p>The behaviour these pins replace cost one round-trip per defect on a payload the caller had to
 * rebuild each time: a 55-operation call named operation 14, and once that was fixed and all 55
 * resent, named operation 27. The shipped tool description already told the agent that
 * "all operations are pre-validated before any execute", which was not true — the loop returned at
 * the first failure and never looked at the rest. These assert the sentence, not just the fix.</p>
 *
 * <p>Two properties are load-bearing and easy to lose. A call with exactly <em>one</em> failing
 * operation must read exactly as it always did — that is most calls, and a count clause there would
 * trade detail for nothing. And an operation that back-references a failed one must be declined out
 * loud: left to resolve, its reference becomes null, which on an optional parameter is dropped in
 * silence and the operation succeeds against a different container.</p>
 *
 * <p>Runs headlessly over a real in-memory model — no display, no OSGi — so the refusal an agent
 * reads is covered by the lane that runs on every change.</p>
 */
public class BulkMultiFailureReportTest {

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actor;
    private boolean approvalMode;
    private int executedCommands;

    private static final String SESSION = "bulk-multi-failure-session";

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk Multi Failure Fixture");
        model.setId("model-bulk-multi-failure");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Target Actor");
        business.getElements().add(actor);

        stubModelManager.setModels(List.of(model));

        approvalMode = false;
        executedCommands = 0;
        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executedCommands++;
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                executedCommands++;
                stack.execute(toPlainCompound(command));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }


    // ---------- the refusal now names every operation that failed ----------

    /**
     * The defect, at the boundary an agent actually meets. Three operations, two independently
     * invalid and failing for different reasons; before this change the refusal named the first
     * and the second was invisible, so the caller fixed one thing, resent all three, and was told
     * about the next.
     */
    @Test
    public void shouldReportEveryFailedOperation_notOnlyTheFirst() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType")),
                        op("create-element", Map.of("elementName", "Gamma", "type", "BusinessRole"))),
                        "description", "three operations, two invalid"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("both failing operations must be reported in one refusal", 2, failed.size());
        assertEquals(1, failed.get(0).get("index"));
        assertEquals(2, failed.get(1).get("index"));
    }

    /**
     * Each row keeps the error code of the operation that produced it. The refusal used to
     * overwrite every inner code with the whole-call code, so an agent could not tell an unknown
     * type from a missing parameter without re-reading the prose.
     */
    @Test
    public void shouldKeepEachOperationsOwnErrorCode_notTheWholeCallCode() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType")),
                        op("create-element", Map.of("elementName", "Gamma", "type", "BusinessRole"))),
                        "description", "distinct inner codes"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("INVALID_ELEMENT_TYPE", failed.get(0).get("errorCode"));
        assertEquals("INVALID_PARAMETER", failed.get(1).get("errorCode"));
        assertEquals("the whole-call code stays on the error object itself",
                "BULK_VALIDATION_FAILED", error(envelope).get("code"));
    }

    /**
     * Rows carry the caller's request index, not their position in the failure list. With the
     * operations between them removed the two diverge, and only the request index lets a caller
     * map a row back to the operation it wrote.
     */
    @Test
    public void shouldUseTheRequestIndex_notThePositionInTheFailureList() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Zero", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "One", "type", "NotARealType")),
                        op("create-element", Map.of("name", "Two", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Three", "type", "AlsoNotReal"))),
                        "description", "operations 1 and 3 fail"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals("request index, not list position", 1, failed.get(0).get("index"));
        assertEquals("request index, not list position", 3, failed.get(1).get("index"));
    }

    /**
     * A dependent operation is declined and said so. Left to resolve, its reference would become
     * null: on a required parameter that reads as a missing parameter the caller did supply, and
     * on an optional one it is dropped in silence and the operation succeeds somewhere else.
     * Both failures appear in one report, under their own request indices.
     */
    @Test
    public void shouldReportTheCascadeAndThePrimaryFailure_together() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Grp", "type", "NotARealType")),
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1",
                                "parentViewObjectId", "$1.id"))),
                        "description", "operation 2 depends on the failed operation 1"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals(1, failed.get(0).get("index"));
        assertEquals("INVALID_ELEMENT_TYPE", failed.get(0).get("errorCode"));
        assertEquals(2, failed.get(1).get("index"));
        assertEquals("a reference to a failed operation must be declined, never resolved to null",
                "BACK_REFERENCE_FAILED", failed.get(1).get("errorCode"));
        assertEquals("Back-reference $1.id unavailable — operation 1 failed",
                failed.get(1).get("message"));
        assertEquals("nothing may be placed on the view", 0, view.getChildren().size());
    }

    // ---------- one failure is exactly what it always was ----------

    /**
     * The single-failure refusal is unchanged, field for field. That is most calls, and a count
     * clause or a one-row array beside an identical message would cost the caller detail it used
     * to get in exchange for nothing it did not already know.
     */
    @Test
    public void shouldLeaveTheErrorUnchanged_whenExactlyOneOperationFails() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType"))),
                        "description", "exactly one invalid operation"));

        Map<String, Object> error = error(envelope);
        assertEquals("BULK_VALIDATION_FAILED", error.get("code"));
        assertEquals("Operation 1 (create-element): Invalid ArchiMate element type: NotARealType",
                error.get("message"));
        assertEquals("failedOperationIndex=1, failedTool=create-element", error.get("details"));
        assertEquals("Fix the failed operation and retry the entire bulk-mutate call",
                error.get("suggestedCorrection"));
        assertFalse("a one-element list beside an identical message is noise",
                error.containsKey("failed"));
    }

    /**
     * The refusal envelope now carries the two sections every other envelope this server emits
     * already had. Pinned rather than left to be noticed: it is a deliberate widening, and no
     * existing assertion anywhere reads either key's absence — every pin on this envelope reads
     * fields inside {@code error}.
     */
    @Test
    public void shouldCarryNextStepsAndModelVersion_onTheRefusalEnvelope() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType"))),
                        "description", "envelope shape"));

        assertTrue("a refusal that says what to do next is the point",
                envelope.get("nextSteps") instanceof List<?> steps && !steps.isEmpty());
        assertTrue(String.valueOf(envelope.get("_meta")),
                meta(envelope).containsKey("modelVersion"));
    }

    // ---------- nothing is applied ----------

    /**
     * Running the loop to completion changes what the caller is told, never what the model holds.
     * Asserted on the model rather than read off the response, because the response is the thing
     * under test.
     */
    @Test
    public void shouldApplyNothing_whenAnyOperationFails() throws Exception {
        int before = model.getFolder(FolderType.BUSINESS).getElements().size();

        invokeTool(registryOverLiveAccessor(), "bulk-mutate", Map.of(
                "operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "BusinessRole")),
                        op("create-element", Map.of("name", "Gamma", "type", "NotARealType")),
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1"))),
                "description", "two valid creates and a placement, one bad type"));

        assertEquals("no element may be created", before,
                model.getFolder(FolderType.BUSINESS).getElements().size());
        assertEquals("nothing may be placed on the view", 0, view.getChildren().size());
        assertEquals("no command may reach the stack", 0, executedCommands);
    }

    /**
     * The refusal is raised before the approval gate is reached, so a call that fails validation
     * in approval mode is refused outright rather than parked as a proposal a human is asked to
     * review. Verified by running it, not by reading the order of the statements.
     */
    @Test
    public void shouldNotParkAProposal_whenValidationFailsInApprovalMode() throws Exception {
        approvalMode = true;

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType"))),
                        "description", "approval mode, invalid payload"));

        assertEquals("BULK_VALIDATION_FAILED", error(envelope).get("code"));
        assertFalse("no proposal may be created", envelope.containsKey("result"));
    }

    /**
     * The same, for an open batch: a failing call is refused rather than queued, so there is no
     * deferred arm here whose projected state would have to be labelled.
     */
    @Test
    public void shouldNotQueueIntoAnOpenBatch_whenValidationFails() throws Exception {
        dispatcher.beginBatch(SESSION, "outer");

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType"))),
                        "description", "open batch, invalid payload"));

        assertEquals("BULK_VALIDATION_FAILED", error(envelope).get("code"));
        assertFalse("nothing may be queued", envelope.containsKey("result"));
    }

    // ---------- one row shape, two envelopes ----------

    /**
     * The rows in the refusal and the rows beside the successes are built by one method. Asserted
     * as a shape comparison rather than by naming the keys twice, so a key added to one and not
     * the other cannot pass.
     */
    @Test
    public void shouldUseTheSameRowShape_onBothTheErrorAndTheSuccessPath() throws Exception {
        List<Object> operations = List.of(
                op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                op("create-element", Map.of("name", "Beta", "type", "NotARealType")),
                op("create-element", Map.of("elementName", "Gamma", "type", "BusinessRole")));

        List<Map<String, Object>> fromError = failedRows(invokeTool(registryOverLiveAccessor(),
                "bulk-mutate", Map.of("operations", operations, "description", "error path")));

        setUp(); // a fresh model, so the successful operation of the second call lands cleanly
        Map<String, Object> success = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "continueOnError", true,
                        "description", "success path"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fromSuccess =
                (List<Map<String, Object>>) result(success).get("failed");

        assertEquals("both paths must publish the same rows in the same key order",
                fromSuccess, fromError);
        assertEquals(List.of("index", "tool", "errorCode", "message"),
                new ArrayList<>(fromError.get(0).keySet()));
        assertEquals(List.of("index", "tool", "errorCode", "message", "suggestedCorrection"),
                new ArrayList<>(fromError.get(1).keySet()));
    }

    // ---------- nextSteps is capped, and says so truthfully ----------

    /**
     * The rows are never capped — every one is a defect the caller must fix, and dropping some
     * would recreate one round-trip per defect. The prose is, and the closing entry states the
     * real remainder rather than a round number.
     */
    @Test
    public void shouldCapNextStepsAndNameTheRealRemainder() throws Exception {
        List<Object> operations = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            operations.add(op("create-element",
                    Map.of("name", "Bad" + i, "type", "NotARealType" + i)));
        }

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "thirteen bad operations"));

        assertEquals("every failure reaches the array, uncapped", 13, failedRows(envelope).size());

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) envelope.get("nextSteps");
        assertEquals("ten named, one remainder clause, one closing entry", 12, steps.size());
        assertTrue(steps.get(10), steps.get(10).startsWith("3 further failed operations are not listed"));
        assertTrue(steps.get(10), steps.get(10).contains("for all 13"));
    }

    // ---------- the handler's own parse loop ----------

    /**
     * The same defect one layer up, and the one an agent hits most: a misspelled tool name is
     * caught before the call ever reaches the model, and the parse loop used to stop at the first.
     * The error code stays the request-shape code rather than being promoted to the whole-call
     * validation code, because these failures are a different thing.
     */
    @Test
    public void shouldReportEveryUnsupportedToolName_notOnlyTheFirst() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-elemnt", Map.of("name", "A", "type", "BusinessActor")),
                        op("creat-relationship", Map.of("name", "B")),
                        op("add-to-veiw", Map.of("viewId", "view-1"))),
                        "description", "three misspelled tool names"));

        assertEquals("INVALID_PARAMETER", error(envelope).get("code"));
        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("all three misspellings must be reported at once", 3, failed.size());
        assertEquals(0, failed.get(0).get("index"));
        assertEquals(1, failed.get(1).get("index"));
        assertEquals(2, failed.get(2).get("index"));
        assertEquals("create-elemnt", failed.get(0).get("tool"));
    }

    /**
     * Malformed entries of every shape are collected together, not just the unsupported-tool one.
     */
    @Test
    public void shouldReportMalformedEntriesOfEveryShape_together() throws Exception {
        List<Object> operations = new ArrayList<>();
        operations.add("not an object at all");
        operations.add(Map.of("params", Map.of("name", "A")));
        operations.add(Map.of("tool", "create-element", "params", "not a map"));
        operations.add(op("nonesuch-tool", Map.of()));

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "four malformed shapes"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("each of the four early exits must collect rather than return", 4, failed.size());
        assertTrue(String.valueOf(failed.get(0).get("message")),
                String.valueOf(failed.get(0).get("message")).contains("must be an object"));
        assertFalse("an entry that is not an object names no tool",
                failed.get(0).containsKey("tool"));
        assertTrue(String.valueOf(failed.get(1).get("message")),
                String.valueOf(failed.get(1).get("message")).contains("'tool' field"));
        assertTrue(String.valueOf(failed.get(2).get("message")),
                String.valueOf(failed.get(2).get("message")).contains("'params' field"));
        assertEquals("create-element", failed.get(2).get("tool"));
        assertTrue(String.valueOf(failed.get(3).get("message")),
                String.valueOf(failed.get(3).get("message")).contains("Unsupported tool"));
    }

    /**
     * One malformed operation still reads exactly as it always did.
     */
    @Test
    public void shouldReturnTheUnchangedMessage_whenOneOperationIsMalformed() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "A", "type", "BusinessActor")),
                        op("create-elemnt", Map.of("name", "B", "type", "BusinessActor"))),
                        "description", "one misspelled tool name"));

        Map<String, Object> error = error(envelope);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(String.valueOf(error.get("message")),
                String.valueOf(error.get("message")).startsWith("Operation at index 1: "));
        assertFalse("a count clause on a single failure says nothing",
                String.valueOf(error.get("message")).contains("could not be read"));
        assertFalse(error.containsKey("failed"));
    }

    /**
     * The parse loop never consults {@code continueOnError}: an operation whose shape cannot be
     * read cannot be executed under any flag, so the flag must not change what is reported.
     */
    @Test
    public void shouldIgnoreContinueOnError_whenAnOperationCannotBeRead() throws Exception {
        List<Object> operations = List.of(
                op("create-elemnt", Map.of("name", "A")),
                op("add-to-veiw", Map.of("viewId", "view-1")));

        Map<String, Object> off = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "flag off"));
        setUp();
        Map<String, Object> on = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "continueOnError", true, "description", "flag on"));

        assertEquals(error(off).get("code"), error(on).get("code"));
        assertEquals(error(off).get("message"), error(on).get("message"));
        assertEquals(failedRows(off), failedRows(on));
    }



    /**
     * A refusal that lists every failure must not republish the same long list once per failure.
     *
     * <p>Found by sending the payload to a running server rather than by any assertion here: three
     * misspelled tool names produced a refusal carrying the full twenty-eight-tool enumeration
     * <em>seven</em> times — once in the top-level message, once in each row's message, again in
     * each row's correction, and again in each guidance entry. The point of reporting every
     * failure at once is to spend one round-trip instead of several; paying for it with a response
     * several times larger than it needs to be gives back what it saved, on a surface an agent
     * reads in full.</p>
     *
     * <p>Counted rather than described. The irreducible amount is <b>three</b>, and none of the
     * three is per-row: the top-level message, the top-level correction, and one dictionary entry
     * the rows reference. The number does not move when the row count does.</p>
     *
     * <p><b>This pin used to assert five, and its reasoning was that one copy per row's message
     * was "the irreducible amount — that is the data".</b> That was judged on this three-row
     * fixture and does not survive being scaled to what the tool actually accepts. The list is not
     * per-row data: it is one fact about the server, identical on every row, while the one per-row
     * fact — the name the caller misspelled — is already in the row's own {@code tool} key and in
     * the head of its message. At the hundred-and-fifty-operation ceiling the old reading licensed
     * a hundred and fifty copies of a five-hundred-character list, tens of kilobytes of one
     * sentence; at three rows it looked like data. A fixture three rows wide cannot see a defect
     * whose whole shape is that it grows with the row count, which is why
     * {@link #shouldCarryARepeatedMessageTailOnce_atTheOperationCeiling} measures it at the
     * ceiling instead.</p>
     */
    @Test
    public void shouldNotRepublishTheToolListOncePerFailure() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-elemnt", Map.of("name", "A")),
                        op("creat-relationship", Map.of("name", "B")),
                        op("add-to-veiw", Map.of("viewId", "view-1"))),
                        "description", "three misspelled tool names"));

        String wire = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(envelope);

        // A tool that appears only when the whole supported-tools list is rendered.
        int copies = 0;
        for (int at = wire.indexOf("delete-specialization"); at >= 0;
                at = wire.indexOf("delete-specialization", at + 1)) {
            copies++;
        }

        assertEquals("the supported-tools list belongs once in the top-level message, once as the "
                + "caller's correction, and once in the dictionary the rows reference — a copy in "
                + "EVERY row is duplication the caller pays for, and it is duplication at three "
                + "rows for the same reason it is at a hundred and fifty: " + wire,
                3, copies);
    }

    /**
     * The same, for the branch the first version of this pin could not see.
     *
     * <p>An operation with no {@code tool} key at all takes a different exit from a misspelled
     * one, and its message does <em>not</em> enumerate the supported tools — so the correction is
     * the caller's only source for that list, and simply deleting it would leave them with no way
     * to learn what is valid. The list is carried once, on the refusal, rather than once per row.
     * The first fix patched only the sibling branch and this pin only drove the sibling branch,
     * which is how a half-applied fix read as a complete one.</p>
     */
    @Test
    public void shouldNotRepublishTheToolList_whenOperationsOmitTheToolKeyEntirely() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        Map.of("params", Map.of("name", "A")),
                        Map.of("params", Map.of("name", "B")),
                        Map.of("params", Map.of("name", "C"))),
                        "description", "three operations with no tool key"));

        String wire = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(envelope);

        int copies = 0;
        for (int at = wire.indexOf("delete-specialization"); at >= 0;
                at = wire.indexOf("delete-specialization", at + 1)) {
            copies++;
        }

        assertEquals("none of these three messages names the supported tools, so the list belongs "
                + "exactly once — on the refusal's own correction, not on all three rows: " + wire,
                1, copies);
        assertEquals("all three must still be reported", 3, failedRows(envelope).size());
    }

    // ---------- one copy of a string every row shares ----------

    /**
     * A string every row carries is carried once for the refusal and referenced from the rows.
     *
     * <p>The saving is a function of how many rows share one string, so it is measured where the
     * tool's own ceiling puts it rather than on the three-row fixture beside it. A hundred and
     * fifty operations naming an unsupported tool used to republish the twenty-eight-tool list
     * once per row's message — the largest repeated string in the tree, and the half of this
     * defect a sibling pin licensed as "the data" while it was only ever measured at three rows.
     * </p>
     *
     * <p><b>This exact fixture was run against the previous projection and counted 152 copies.</b>
     * That is the number the old "one copy per row is the data" reading called irreducible, and it
     * is why three rows was the wrong place to judge it: the same reasoning that looked defensible
     * at five copies licenses a hundred and fifty-two. Stated as a measurement rather than left to
     * the prose beside it, so the contrast is evidence.</p>
     */
    @Test
    public void shouldCarryARepeatedMessageTailOnce_atTheOperationCeiling() throws Exception {
        List<Object> operations = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            operations.add(op("bad-tool-" + i, Map.of("name", "A")));
        }
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "every operation misspells a tool"));

        assertEquals("every operation must still be reported", 150, failedRows(envelope).size());

        String wire = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(envelope);
        int copies = countCopies(wire, "delete-specialization");
        assertEquals("a hundred and fifty rows must cost exactly what three rows cost — the "
                + "top-level message, the caller's correction and one dictionary entry — because "
                + "the count of a string every row shares must not scale with the rows: " + copies,
                3, copies);
    }

    /**
     * Nothing is lost. Reassembling each row from its own head and the refusal's dictionary yields
     * exactly the string the row used to carry in full.
     *
     * <p>This is what separates carrying a string once from truncating it. A reference is an index
     * into data, and it is only honest while the data it indexes is present and complete — so the
     * assertion is byte equality against the un-deduped value, not a containment check that a
     * shortened string would also pass.</p>
     */
    @Test
    public void shouldReassembleEveryDedupedRow_byteForByte() throws Exception {
        List<Object> operations = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            operations.add(op("bad-tool-" + i, Map.of("name", "A")));
        }
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "four misspelled tool names"));

        List<Map<String, Object>> rows = failedRows(envelope);
        assertTrue("the fixture must actually be deduped, or this pin proves nothing",
                rows.stream().anyMatch(row -> row.containsKey("messageRef")));

        for (int i = 0; i < rows.size(); i++) {
            assertEquals("row " + i + " must reassemble to the whole message",
                    "Operation at index " + i + ": Unsupported tool 'bad-tool-" + i + "'. Supported: "
                            + net.vheerden.archi.mcp.response.dto.BulkOperation.SUPPORTED_TOOLS_ORDERED,
                    reassemble(envelope, rows.get(i), "message", "messageRef", "messages"));
        }
    }

    /**
     * The dictionary and the rows cannot disagree in either direction: every reference a row names
     * resolves, and the dictionary carries no entry no row names.
     *
     * <p>A dangling reference is the "flag as an index into missing data" failure this repository
     * already rules a correctness defect, and an orphaned entry is the same defect read the other
     * way — bytes the caller pays for and can never use. Both directions are asserted because a
     * fix that builds the dictionary from one collection and the references from another can be
     * wrong in only one of them.</p>
     */
    @Test
    public void shouldResolveEveryReference_andNameEveryDictionaryEntry() throws Exception {
        List<Object> operations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            operations.add(op("bad-tool-" + i, Map.of("name", "A")));
        }
        operations.add(op("update-view-object",
                Map.of("viewId", "view-1", "viewObjectId", "ghost-a", "x", 10)));
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "six misspelled names"));

        assertReferencesAndDictionaryAgree(envelope);
    }

    /**
     * Every row stays self-describing: whether a key is present on row n depends only on row n's
     * own content, never on what row n-1 happened to carry.
     *
     * <p>That is the property a delta encoding against the previous row destroys and the one that
     * lets a client parse row n without having read the rows before it. Pinned on a genuine
     * mixture, because a fixture where every row is alike cannot tell the two encodings apart:
     * two rows share a long remedy and reference it, one carries a remedy of its own that nothing
     * else shares, and one carries none at all — three shapes in one refusal, each decided by that
     * row's content alone.</p>
     *
     * <p>The mixture has to be built from operations that are all <em>well-formed</em>. An
     * operation whose shape cannot be read takes a different exit and refuses the whole call
     * before any of the others are validated, so a payload mixing the two kinds reports only the
     * unreadable ones and quietly tests nothing.</p>
     */
    @Test
    public void shouldKeepEveryRowSelfDescribing_whenTheRowsCarryAMixture() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("update-view-object", Map.of("viewId", "view-1",
                                "viewObjectId", "ghost-a", "x", 10)),
                        op("update-view-object", Map.of("viewId", "view-1",
                                "viewObjectId", "ghost-b", "x", 10)),
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType")),
                        op("create-element", Map.of("elementName", "Gamma", "type", "BusinessRole"))),
                        "description", "shared, unique and absent corrections in one refusal"));

        List<Map<String, Object>> rows = failedRows(envelope);
        assertEquals(4, rows.size());

        // Two rows share one long remedy: it moves to the dictionary and they name it.
        for (Map<String, Object> shared : List.of(rows.get(0), rows.get(1))) {
            assertEquals("VIEW_OBJECT_NOT_FOUND", shared.get("correctionRef"));
            assertFalse("a row that references the remedy must not also carry it: " + shared,
                    shared.containsKey("suggestedCorrection"));
            assertTrue("and must keep its own account of what went wrong: " + shared,
                    String.valueOf(shared.get("message")).contains("ghost-"));
        }

        // A row whose failure carried no remedy at all names nothing and carries nothing.
        Map<String, Object> absent = rows.get(2);
        assertFalse("no remedy to carry: " + absent, absent.containsKey("suggestedCorrection"));
        assertFalse("and so nothing to reference: " + absent, absent.containsKey("correctionRef"));

        // A row whose remedy is its own keeps it in place — one row shares with nobody.
        Map<String, Object> unique = rows.get(3);
        assertEquals("Provide a non-empty string value for 'name'",
                unique.get("suggestedCorrection"));
        assertFalse("a remedy only one row carries stays on that row: " + unique,
                unique.containsKey("correctionRef"));

        assertEquals("three shapes, each decided by its own row's content",
                3, rows.stream().map(row -> new ArrayList<>(row.keySet()))
                        .distinct().count());
        assertReferencesAndDictionaryAgree(envelope);
    }

    /**
     * The degenerate end: a refusal whose rows share nothing must not grow by a single byte.
     *
     * <p>A dictionary is closest to costing more than it saves where there is nothing to share,
     * and a mechanism that pays for itself only on the large case is not one worth having on the
     * small one. These two rows fail under different codes, with different messages, and one
     * carries a remedy the other does not — so no group has two members and nothing is carried
     * away from either row.</p>
     *
     * <p><b>Measured, not argued: this refusal is 990 bytes, and it was 990 bytes before the
     * mechanism existed</b> — the same payload run against the previous projection produced the
     * identical count. That is what "no more than the dictionary's own overhead" comes to when the
     * dictionary correctly declines to exist: exactly zero. Asserted structurally as well as by
     * the number, because a byte count alone would also pass if the fields had changed and
     * happened to balance.</p>
     */
    @Test
    public void shouldNotGrowATwoRowRefusal_whenTheRowsShareNothing() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Beta", "type", "NotARealType")),
                        op("create-element", Map.of("elementName", "Gamma", "type", "BusinessRole"))),
                        "description", "two failures with nothing in common"));

        Map<String, Object> error = error(envelope);
        assertFalse("nothing is shared, so no message dictionary is worth its bytes: " + error,
                error.containsKey("messages"));
        assertFalse("nor a corrections dictionary: " + error,
                error.containsKey("corrections"));

        List<Map<String, Object>> rows = failedRows(envelope);
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            assertFalse("no row references anything: " + row, row.containsKey("messageRef"));
            assertFalse(row.containsKey("correctionRef"));
            assertNotNull("and every row keeps its whole message: " + row, row.get("message"));
        }
        assertEquals("the row whose failure carried a remedy keeps it in full",
                "Provide a non-empty string value for 'name'",
                rows.get(1).get("suggestedCorrection"));
        assertFalse("and the one whose failure carried none still carries none",
                rows.get(0).containsKey("suggestedCorrection"));
    }

    /**
     * A short string every row shares stays on the rows.
     *
     * <p>Carrying something once is only a saving while the string is longer than the keys that
     * replace it. Every malformed row ends with the same fifty-character remedy, and moving that
     * to a dictionary would cost a reference on each row plus the entry itself and hand the caller
     * back fewer bytes than it took — while making them resolve an indirection to read half a
     * line. The mechanism has to decline the cases it cannot pay for, or it stops being a saving
     * and becomes a shape change applied for its own sake.</p>
     */
    @Test
    public void shouldLeaveAShortSharedCorrectionOnEveryRow_ratherThanPayForADictionary()
            throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("bad-tool-a", Map.of("name", "A")),
                        op("bad-tool-b", Map.of("name", "B")),
                        op("bad-tool-c", Map.of("name", "C"))),
                        "description", "three rows sharing one short remedy"));

        List<Map<String, Object>> rows = failedRows(envelope);
        assertEquals(3, rows.size());
        String shared = (String) rows.get(0).get("suggestedCorrection");
        assertTrue("the fixture must share a correction that is genuinely short: " + shared,
                shared != null && shared.length() < 80);
        for (Map<String, Object> row : rows) {
            assertEquals("every row keeps the short remedy in place", shared,
                    row.get("suggestedCorrection"));
            assertFalse("and none of them references it: " + row,
                    row.containsKey("correctionRef"));
        }
        assertFalse("no corrections dictionary is worth its own bytes here: " + error(envelope),
                error(envelope).containsKey("corrections"));
    }

    /**
     * The third collector. Operations that are well-formed and then fail validation are gathered
     * by a different builder from the two above, and it repeats what its rows share exactly as
     * they did.
     *
     * <p>Worth its own pin because neither earlier pass touched this one: the malformed loop got a
     * single correction on the refusal and the guidance entries were cut to one line each, while
     * the collector behind the validation refusal was left copying whatever the dispatch threw,
     * verbatim, once per operation. It is also the widest of the three — it records the failure of
     * any of the twenty-eight tools — so a fix that reaches only the other two leaves the largest
     * surface untouched while every visible count looks improved.</p>
     */
    @Test
    public void shouldCarryASharedCorrectionOnce_whenManyOperationsFailValidation() throws Exception {
        List<Object> operations = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            operations.add(op("update-view-object", Map.of("viewId", "view-1",
                    "viewObjectId", "ghost-" + i, "x", 10)));
        }
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", operations, "description", "twelve stale view object ids"));

        List<Map<String, Object>> rows = failedRows(envelope);
        assertEquals("every operation must still be reported", 12, rows.size());
        for (Map<String, Object> row : rows) {
            assertTrue("no row may still carry its own copy of the shared remedy: " + row,
                    row.containsKey("correctionRef"));
            assertFalse(row.containsKey("suggestedCorrection"));
            assertTrue("and every row keeps its own account of what went wrong: " + row,
                    String.valueOf(row.get("message")).contains("ghost-"));
        }
        assertReferencesAndDictionaryAgree(envelope);

        String wire = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(envelope);
        assertEquals("the advice belongs once on the refusal and once in the dictionary the rows "
                + "reference, not twelve times: " + wire,
                2, countCopies(wire, "back-reference THAT operation instead"));
    }

    /**
     * A row's own text stops where a sentence stops.
     *
     * <p>The shared ending is found by counting matching characters backwards, and characters can
     * match for no reason: these messages agree on a closing quote and a full stop before they
     * agree on anything a reader would call a shared clause. Splitting only where a sentence ends
     * is what keeps the head readable on its own by a client that never resolves the reference,
     * and it is also what keeps the boundary off the middle of a character, which counting alone
     * does not guarantee.</p>
     *
     * <p>Driven from the malformed refusal because that is the only path where a head survives at
     * all: where every row's value is identical the whole of it moves and there is no boundary to
     * get wrong, so a fixture built there would assert this rule without ever reaching it.</p>
     */
    @Test
    public void shouldSplitOnlyWhereASentenceEnds() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("bad-tool-a", Map.of("name", "A")),
                        op("bad-tool-b", Map.of("name", "B")),
                        op("bad-tool-c", Map.of("name", "C"))),
                        "description", "three misspelled tool names"));

        @SuppressWarnings("unchecked")
        Map<String, Object> messages = (Map<String, Object>) error(envelope).get("messages");
        assertNotNull("the fixture must actually split a message, or this pin asserts nothing",
                messages);

        int split = 0;
        for (Map<String, Object> row : failedRows(envelope)) {
            if (!row.containsKey("messageRef")) {
                continue;
            }
            split++;
            String head = (String) row.get("message");
            String tail = (String) messages.get(row.get("messageRef"));
            assertNotNull("a split row must keep a head of its own: " + row, head);
            assertTrue("a head a client may read alone must end where a sentence ends: " + head,
                    head.endsWith("."));
            assertTrue("and the shared clause must begin at that boundary, not inside a token the "
                    + "rows agreed on by accident: " + tail, tail.startsWith(" "));
        }
        assertEquals("every row of this fixture must have been split", 3, split);
    }

    private static int countCopies(String wire, String needle) {
        int copies = 0;
        for (int at = wire.indexOf(needle); at >= 0; at = wire.indexOf(needle, at + 1)) {
            copies++;
        }
        return copies;
    }

    /** Rebuilds one row's field from its own head and the refusal's dictionary. */
    private static String reassemble(Map<String, Object> envelope, Map<String, Object> row,
            String field, String refKey, String dictKey) {
        String head = row.get(field) != null ? (String) row.get(field) : "";
        if (!row.containsKey(refKey)) {
            return head;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dictionary = (Map<String, Object>) dictionaryHolder(envelope).get(dictKey);
        return head + (String) dictionary.get(row.get(refKey));
    }

    /** Wherever the rows are, the dictionary is their sibling. */
    private static Map<String, Object> dictionaryHolder(Map<String, Object> envelope) {
        Map<String, Object> error = envelope.containsKey("error") ? error(envelope) : null;
        return error != null && error.containsKey("failed") ? error : result(envelope);
    }

    private static void assertReferencesAndDictionaryAgree(Map<String, Object> envelope) {
        Map<String, Object> holder = dictionaryHolder(envelope);
        for (String pair : List.of("message:messageRef:messages",
                "suggestedCorrection:correctionRef:corrections")) {
            String[] parts = pair.split(":");
            @SuppressWarnings("unchecked")
            Map<String, Object> dictionary = (Map<String, Object>) holder.get(parts[2]);
            Set<String> named = new java.util.LinkedHashSet<>();
            for (Map<String, Object> row : failedRows(envelope)) {
                if (row.containsKey(parts[1])) {
                    String key = (String) row.get(parts[1]);
                    assertTrue("row names " + parts[1] + "=" + key + " but no " + parts[2]
                            + " dictionary is published", dictionary != null);
                    assertTrue("row names " + parts[1] + "=" + key + " which " + parts[2]
                            + " does not resolve: " + dictionary, dictionary.containsKey(key));
                    named.add(key);
                }
            }
            if (dictionary != null) {
                assertEquals(parts[2] + " must carry no entry no row names",
                        named, dictionary.keySet());
            }
        }
    }

    /**
     * A call with one malformed operation must read exactly as it always did — correction
     * included, not just the message.
     *
     * <p>Shortening the row correction to stop it repeating per failure quietly shortened this
     * too, because the refusal takes its correction from the first row. The caller of a
     * single-operation mistake would have lost the list of valid tools for no gain, which is the
     * cost the one-versus-many rule exists to prevent.</p>
     */
    @Test
    public void shouldKeepTheFullCorrection_whenOneOperationIsMalformed() throws Exception {
        Map<String, Object> misspelled = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(op("create-elemnt", Map.of("name", "A"))),
                        "description", "one misspelled tool name"));
        assertEquals("Use a supported tool: " + net.vheerden.archi.mcp.response.dto.BulkOperation
                        .SUPPORTED_TOOLS,
                error(misspelled).get("suggestedCorrection"));

        setUp();
        Map<String, Object> missing = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(Map.of("params", Map.of("name", "A"))),
                        "description", "one operation with no tool key"));
        assertEquals("Provide a valid tool name: " + net.vheerden.archi.mcp.response.dto.BulkOperation
                        .SUPPORTED_TOOLS,
                error(missing).get("suggestedCorrection"));
    }

    /**
     * A back-reference index too large to be an {@code int} must fail as that operation's own
     * refusal, not as an internal error that takes the whole report with it.
     *
     * <p>The pattern accepts any run of digits, so the value matches and then overflows the parse.
     * A bare {@link Integer#parseInt} throws a {@link NumberFormatException}, which is not the
     * exception type the per-operation catch handles — it escapes the loop entirely and every
     * failure collected before it is discarded. The caller of a call that reports all its failures
     * at once would get strictly less back than when the call stopped at the first one, which
     * inverts the whole point of collecting them.</p>
     */
    @Test
    public void shouldReportAnOversizedBackReference_withoutDiscardingTheFailuresAlreadyFound()
            throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Bad", "type", "NotARealType")),
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1",
                                "parentViewObjectId", "$99999999999.id"))),
                        "description", "an operation index too large to be an int"));

        assertEquals("the refusal must still be the validation refusal, not an internal error",
                "BULK_VALIDATION_FAILED", error(envelope).get("code"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("the earlier failure must survive: " + envelope, 2, failed.size());
        assertEquals(0, failed.get(0).get("index"));
        assertEquals("INVALID_ELEMENT_TYPE", failed.get(0).get("errorCode"));
        assertEquals(1, failed.get(1).get("index"));
        assertEquals("INVALID_PARAMETER", failed.get(1).get("errorCode"));
        assertTrue(String.valueOf(failed.get(1).get("message")),
                String.valueOf(failed.get(1).get("message")).contains("too "));
    }

    /**
     * The same oversized index, on the OTHER parser.
     *
     * <p>Two methods read the digits out of a back-reference, and which one runs depends on
     * whether anything has failed yet: with an earlier failure the cascade check parses first and
     * the resolver is never reached. A fixture that always has an earlier failure therefore covers
     * one parser and certifies nothing about the other — mutating the resolver's parse left the
     * first version of this pin green. Here nothing has failed, so the cascade check is skipped
     * and the resolver is the one that must refuse.</p>
     */
    @Test
    public void shouldReportAnOversizedBackReference_whenNothingHasFailedYet() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1",
                                "parentViewObjectId", "$99999999999.id"))),
                        "description", "an oversized index with no earlier failure"));

        Map<String, Object> error = error(envelope);
        assertEquals("the resolver must refuse it as this operation's failure, not escape as an "
                + "internal error: " + envelope,
                "BULK_VALIDATION_FAILED", error.get("code"));
        assertTrue(String.valueOf(error.get("message")),
                String.valueOf(error.get("message")).startsWith("Operation 0 (add-to-view):"));
        assertTrue(String.valueOf(error.get("message")),
                String.valueOf(error.get("message")).contains("too large"));
        assertEquals("nothing may be placed on the view", 0, view.getChildren().size());
    }

    // ---------- the prepare phase leaves no state behind when it refuses ----------

    /**
     * A refused rename must leave the in-flight specialization cache exactly as it found it.
     *
     * <p>The cache was re-keyed before the new name was validated, so a rename this method goes on
     * to reject stranded the cache with the old key gone and the new key bound to a profile that is
     * never renamed. A specialization created earlier in the same call lives only in that cache —
     * it is not in the model until the commands run — so the next operation to name it found
     * nothing at all.</p>
     *
     * <p>Latent while the call stopped at the first failure. It stops being latent the moment the
     * loop is allowed to keep going, which is what makes this part of the same change rather than a
     * separate one.</p>
     */
    @Test
    public void shouldLeaveTheSpecializationCacheIntact_whenARenameIsRefused() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-specialization",
                                Map.of("name", "Core", "conceptType", "BusinessActor")),
                        // rejected: the model stores text verbatim, so a literal entity is refused
                        op("update-specialization", Map.of("name", "Core",
                                "conceptType", "BusinessActor", "newName", "R&amp;D")),
                        op("update-specialization", Map.of("name", "Core",
                                "conceptType", "BusinessActor", "newName", "Renamed"))),
                        "description", "a refused rename must not strand the cache"));

        Map<String, Object> error = error(envelope);
        assertFalse("only the refused rename may fail — the operation after it must still find the "
                + "specialization created earlier in this call, and a second row here means it did "
                + "not: " + error,
                error.containsKey("failed"));
        assertEquals("failedOperationIndex=1, failedTool=update-specialization",
                error.get("details"));
    }

    /**
     * The icon-band reservation is the one prepare-phase write that still happens before its
     * method ends, and it is safe only because nothing after it can throw.
     *
     * <p>Every other deferred write in the prepare family is the last thing its method does, so a
     * dropped operation leaves nothing behind. This one is not, and the four statements that follow
     * it were read and none can fail — so the reservation was pinned rather than moved. That
     * argument expires the moment a fifth statement is added, and the call now keeps going past a
     * failed operation, so a stranded reservation would be read by a later operation in the same
     * call rather than discarded with the exception.</p>
     *
     * <p>Asserts the mechanism, not a phrase: it names the calls that currently sit in that window
     * and fails on any call that is not one of them, so the guarantee has to be re-derived by
     * whoever widens it. Comments are stripped first, or a javadoc naming a method would satisfy
     * the pin it exists to trip.</p>
     */
    @Test
    public void nothingBetweenTheIconBandReservationAndTheEndOfAddToViewMayThrow() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        int reservation = facade.indexOf("IconBandReservation.reserve(");
        assertTrue("the reservation call must still exist for this gate to cover anything",
                reservation >= 0);
        int end = facade.indexOf("return new PreparedMutation<>(cmd, resultDto, "
                + "diagramObj.getId(), diagramObj);", reservation);
        assertTrue("the end of prepareAddToView must still be locatable", end >= 0);

        String window = stripComments(facade.substring(reservation, end));

        Set<String> permitted = Set.of(
                "IconBandReservation.reserve", "ImageHelper.iconCornerOrNone",
                "mutationDispatcher.queuedBounds", "bulkPendingGroupBounds.get",
                "bulkPendingParents.get", "mutationDispatcher.queuedParents",
                "AnchorResolver.wrapWithIconBandResize", "RecedeContainerFillCommand.wrap",
                "guardPlacement", "AnchorResolver.recordPendingParent",
                "AnchorResolver.projectMoves", "List.copyOf", "diagramObj.getId",
                "element.getName", "new AddToViewResultDto");

        // Control flow is not a call. Without this the gate trips on the first `if` anyone adds
        // and reports it as an unknown method, which would send the next reader looking for a
        // call that is not there — and invite them to silence the gate by listing keywords as if
        // they were permitted callees.
        Set<String> keywords = Set.of("if", "for", "while", "switch", "catch", "return",
                "synchronized", "do", "else", "try", "assert");

        List<String> unexpected = new ArrayList<>();
        Matcher call = Pattern.compile("(new\\s+[A-Z]\\w*|[A-Za-z_][\\w.]*)\\s*\\(").matcher(window);
        while (call.find()) {
            String callee = call.group(1).replaceAll("\\s+", " ").trim();
            if (!keywords.contains(callee) && !permitted.contains(callee)) {
                unexpected.add(callee);
            }
        }

        assertTrue("a call this gate does not know about now sits between the icon-band "
                + "reservation and the end of prepareAddToView. The reservation is written "
                + "mid-method and is safe only while nothing after it can fail; a dropped "
                + "operation would otherwise strand it for a later operation in the same call to "
                + "read. Either prove the new call cannot throw and add it here, or move the "
                + "reservation to the end of the method as the rest of the prepare family does: "
                + unexpected, unexpected.isEmpty());
    }

    /** Comments are prose, not code: a javadoc naming a method must not satisfy the gate. */
    private static String stripComments(String java) {
        return java.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** Reads a production source file, failing rather than silently covering nothing. */
    private static String readProductionSource(String relativePath) {
        for (String candidate : new String[]{
                "../net.vheerden.archi.mcp/src", "net.vheerden.archi.mcp/src"}) {
            java.nio.file.Path path =
                    java.nio.file.Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(path),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("Could not resolve " + relativePath
                + " from " + java.nio.file.Paths.get("").toAbsolutePath()
                + " — this gate cannot silently cover nothing");
    }

    // ---- named references land in the same report, under their own request indices -------------

    /**
     * A name is a second way to write a back-reference, so its refusals must arrive the same way
     * every other pre-validation refusal does: as rows in one report, each under the request index
     * of the operation at fault. Asserted here rather than only where the naming rules themselves
     * are pinned, because reaching this array is a property of the reporting path, not of the
     * resolver — a refusal raised somewhere the collector does not see would be a single-failure
     * abort and no test of the rules would notice.
     */
    @Test
    public void shouldReportAnUndeclaredNameAndAnotherFailure_together() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Grp", "type", "NotARealType")),
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1",
                                "parentViewObjectId", "$coreBanking.id"))),
                        "description", "an unknown name beside an unrelated failure"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("both failures must be reported together: " + failed, 2, failed.size());
        assertEquals("request index, not list position", 1, failed.get(0).get("index"));
        assertEquals(2, failed.get(1).get("index"));
        assertEquals("INVALID_PARAMETER", failed.get(1).get("errorCode"));
        assertTrue("the row must name the label the caller wrote: " + failed.get(1),
                String.valueOf(failed.get(1).get("message")).contains("coreBanking"));
    }

    /**
     * Both declarations of a repeated name reach the array, under their own indices.
     *
     * <p>Refusing only the later one would leave a reference resolving to the earlier — answering
     * by position after all, which is the thing a name exists to stop. Refusing both is what puts
     * the declaring operation in the failed set so a reference cascades loudly, and that is only
     * true if both rows actually arrive here.</p>
     */
    @Test
    public void shouldReportBothDeclarationsOfARepeatedName_underTheirOwnIndices() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        Map.of("tool", "create-element", "as", "paymentsZone",
                                "params", Map.of("name", "Beta", "type", "BusinessActor")),
                        op("create-element", Map.of("name", "Gamma", "type", "BusinessActor")),
                        Map.of("tool", "create-element", "as", "paymentsZone",
                                "params", Map.of("name", "Delta", "type", "BusinessActor"))),
                        "description", "one name claimed by two operations"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("both declarations must be reported: " + failed, 2, failed.size());
        assertEquals(1, failed.get(0).get("index"));
        assertEquals(3, failed.get(1).get("index"));
        for (Map<String, Object> row : failed) {
            assertTrue("each row must name both positions: " + row,
                    String.valueOf(row.get("message")).contains("index 1")
                            && String.valueOf(row.get("message")).contains("index 3"));
        }
    }

    /**
     * A name whose declaring operation failed cascades by name, and the cascade row sits beside the
     * primary failure exactly as the positional form's does two tests above.
     */
    @Test
    public void shouldReportTheCascadeByName_besideThePrimaryFailure() throws Exception {
        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        op("create-element", Map.of("name", "Alpha", "type", "BusinessActor")),
                        Map.of("tool", "create-element", "as", "coreBanking",
                                "params", Map.of("name", "Grp", "type", "NotARealType")),
                        op("add-to-view", Map.of("viewId", "view-1", "elementId", "actor-1",
                                "parentViewObjectId", "$coreBanking.id"))),
                        "description", "the operation that declared the name fails"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals(1, failed.get(0).get("index"));
        assertEquals(2, failed.get(1).get("index"));
        assertEquals("BACK_REFERENCE_FAILED", failed.get(1).get("errorCode"));
        assertTrue("the cascade must name the label, not only a position: " + failed.get(1),
                String.valueOf(failed.get(1).get("message")).contains("coreBanking"));
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> envelope) {
        Object error = envelope.get("error");
        if (!(error instanceof Map<?, ?>)) {
            throw new AssertionError("expected an error envelope, got: " + envelope);
        }
        return (Map<String, Object>) error;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> envelope) {
        Object result = envelope.get("result");
        if (!(result instanceof Map<?, ?>)) {
            throw new AssertionError("expected a success envelope, got: " + envelope);
        }
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("_meta");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> failedRows(Map<String, Object> envelope) {
        Object rows = error(envelope).get("failed");
        if (!(rows instanceof List<?>)) {
            throw new AssertionError("expected a 'failed' array in the error, got: " + envelope);
        }
        return (List<Map<String, Object>>) rows;
    }

    private CommandRegistry registryOverLiveAccessor() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
        return registry;
    }

    private Map<String, Object> invokeTool(CommandRegistry registry, String toolName,
            Map<String, Object> args) throws Exception {
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec =
                registry.getToolSpecifications().stream()
                        .filter(s -> s.tool().name().equals(toolName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(toolName, args));
        io.modelcontextprotocol.spec.McpSchema.TextContent content =
                (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content.text(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
