package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * An {@code apply-positions} call that fails validation reports every entry that failed, not the
 * first one and then silence.
 *
 * <p>The behaviour these pins replace cost one round-trip per defect on a payload the caller had to
 * resend whole each time. The tool accepts ten thousand entries, and the case that produces the
 * most failures is the ordinary one rather than the pathological one: an agent replaying a saved
 * layout onto a view that was cleared and rebuilt has every id stale.</p>
 *
 * <p>Four walks used to end at their first bad entry — the two that read the request shapes and the
 * two that resolve the ids — and the two stages compound: a malformed {@code positions} entry
 * stopped the call before the {@code connections} array was read at all, so a whole array of
 * defects stayed invisible however many round-trips the caller spent on it.</p>
 *
 * <p>Two properties are load-bearing and easy to lose. A call with exactly <em>one</em> failing
 * entry must read exactly as it always did — that is most calls, and a count clause there would
 * trade detail for nothing. And the guards that run before either walk must stay in front of it:
 * an absent view and an oversized payload are answered without preparing anything.</p>
 *
 * <p>Runs headlessly over a real in-memory model — no display, no OSGi — so the refusal an agent
 * reads is covered by the lane that runs on every change.</p>
 */
public class LayoutMultiFailureReportTest {

    private static final String SESSION = "layout-multi-failure-session";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private String connectionId;
    private int executedCommands;
    private boolean approvalMode;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Layout Multi Failure Fixture");
        model.setId("model-layout-multi-failure");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor a = actor(business, "actor-a", "Alpha");
        IBusinessActor b = actor(business, "actor-b", "Beta");
        IBusinessActor c = actor(business, "actor-c", "Gamma");
        IBusinessActor d = actor(business, "actor-d", "Delta");

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(a);
        rel.setTarget(b);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        liveObject("vo-a", a, 0, 0);
        liveObject("vo-b", b, 400, 0);
        liveObject("vo-c", c, 0, 200);
        liveObject("vo-d", d, 400, 200);

        stubModelManager.setModels(List.of(model));

        executedCommands = 0;
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
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
        approvalMode = false;
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
        dispatcher.onModelActive(model);

        connectionId = accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                "vo-a", "vo-b", null, null, null, null, null)
                .entity().viewConnectionId();
        executedCommands = 0;
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    private IBusinessActor actor(IFolder business, String id, String name) {
        IBusinessActor a = factory.createBusinessActor();
        a.setId(id);
        a.setName(name);
        business.getElements().add(a);
        return a;
    }

    private void liveObject(String id, IBusinessActor element, int x, int y) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, 120, 55);
        view.getChildren().add(obj);
    }

    // ---------- helpers that build a payload ----------

    private static Map<String, Object> pos(Object id, int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("viewObjectId", id);
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    private static Map<String, Object> conn(Object id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("viewConnectionId", id);
        m.put("bendpoints", List.of());
        return m;
    }

    private Map<String, Object> apply(Object positions, Object connections) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "view-1");
        if (positions != null) {
            args.put("positions", positions);
        }
        if (connections != null) {
            args.put("connections", connections);
        }
        return invokeTool(registryOverLiveAccessor(), "apply-positions", args);
    }

    // ---------- the refusal now names every entry that failed ----------

    /**
     * The defect, at the boundary an agent actually meets. Two stale ids in one array: before this
     * change the refusal named the first, and the caller fixed one id, resent the whole payload and
     * was told about the next.
     */
    @Test
    public void shouldReportEveryFailedPosition_notOnlyTheFirst() throws Exception {
        Map<String, Object> envelope =
                apply(List.of(pos("ghost-1", 10, 10), pos("ghost-2", 20, 20)), null);

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("both stale ids must be reported in one refusal", 2, failed.size());
        assertEquals("ghost-1", failed.get(0).get("id"));
        assertEquals("ghost-2", failed.get(1).get("id"));
    }

    /** The connections walk has the same defect and the same fix. */
    @Test
    public void shouldReportEveryFailedConnection_notOnlyTheFirst() throws Exception {
        Map<String, Object> envelope =
                apply(null, List.of(conn("ghost-c1"), conn("ghost-c2")));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("both stale connection ids must be reported", 2, failed.size());
        assertEquals("connections", failed.get(0).get("array"));
        assertEquals("connections", failed.get(1).get("array"));
    }

    /**
     * The cross-loop case, and the one a test covering only none-and-both cannot see: a bad
     * positions entry used to end the call before the connections array was examined at all, so a
     * whole array of defects stayed invisible however many round-trips the caller spent.
     */
    @Test
    public void shouldWalkBothArrays_whenThePositionsArrayAlreadyFailed() throws Exception {
        Map<String, Object> envelope =
                apply(List.of(pos("ghost-1", 10, 10)), List.of(conn("ghost-c1")));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("one refusal must name entries from both arrays", 2, failed.size());
        assertEquals("positions", failed.get(0).get("array"));
        assertEquals("connections", failed.get(1).get("array"));
    }

    // ---------- one failing entry reads exactly as it always did ----------

    /**
     * The property that keeps every existing pin honest rather than merely green. Most calls fail
     * on one entry, and there a count clause would trade detail for nothing.
     */
    @Test
    public void shouldLeaveTheErrorObjectUnchanged_whenExactlyOnePositionFails() throws Exception {
        Map<String, Object> error = error(apply(List.of(pos("ghost-1", 10, 10)), null));

        assertEquals("VIEW_OBJECT_NOT_FOUND", error.get("code"));
        assertEquals("Position entry [0] (viewObjectId='ghost-1'): View object not found: ghost-1",
                error.get("message"));
        assertFalse("a one-row array beside identical scalars is noise",
                error.containsKey("failed"));
        assertFalse("details was null on this path and must stay null",
                error.containsKey("details"));
        assertFalse("suggestedCorrection was null on this path and must stay null",
                error.containsKey("suggestedCorrection"));
        assertFalse("archiMateReference was null on this path and must stay null",
                error.containsKey("archiMateReference"));
    }

    /** The connection half of the same guarantee. */
    @Test
    public void shouldLeaveTheErrorObjectUnchanged_whenExactlyOneConnectionFails() throws Exception {
        Map<String, Object> error = error(apply(null, List.of(conn("ghost-c1"))));

        assertEquals("VIEW_OBJECT_NOT_FOUND", error.get("code"));
        assertEquals("Connection entry [0] (viewConnectionId='ghost-c1'): "
                + "View object not found: ghost-c1", error.get("message"));
        assertFalse(error.containsKey("failed"));
        assertFalse(error.containsKey("suggestedCorrection"));
    }

    // ---------- what each row says ----------

    /**
     * Each row keeps the code its own entry failed with. Flattening them to one whole-call code
     * would destroy the inner code this tool, uniquely, already preserved — and the scalar code is
     * the first failure's, so a fixture whose second entry fails differently is the only one that
     * can see the difference.
     */
    @Test
    public void shouldKeepEachRowsOwnErrorCode_whenEntriesFailForDifferentReasons()
            throws Exception {
        // The second entry names a live object but asks for nothing, which the same walk rejects
        // as a parameter fault rather than a lookup failure.
        Map<String, Object> envelope = apply(
                List.of(pos("ghost-1", 10, 10), Map.of("viewObjectId", "vo-a")), null);

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals("a stale id keeps its own code",
                "VIEW_OBJECT_NOT_FOUND", failed.get(0).get("errorCode"));
        assertEquals("an entry rejected for a different reason keeps its own, different code",
                "INVALID_PARAMETER", failed.get(1).get("errorCode"));
        assertEquals("and the scalar code still describes the first failure alone",
                "VIEW_OBJECT_NOT_FOUND", error(envelope).get("code"));
    }

    /**
     * The index is the caller's own request index, not a position in the failure list. A caller
     * handed 0 and 1 for entries it sent at 1 and 3 cannot find either of them.
     */
    @Test
    public void shouldReportEachEntrysOwnRequestIndex_notItsPositionInTheList() throws Exception {
        Map<String, Object> envelope = apply(List.of(
                pos("vo-a", 1, 1), pos("ghost-1", 2, 2),
                pos("vo-b", 3, 3), pos("ghost-2", 4, 4)), null);

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals("the second entry the caller sent", 1, failed.get(0).get("index"));
        assertEquals("the fourth entry the caller sent", 3, failed.get(1).get("index"));
    }

    /**
     * Two arrays, one index space each. {@code index 0} alone names two different entries, so a row
     * that carried only an index would be unusable exactly when both arrays are wrong.
     */
    @Test
    public void shouldDistinguishTheSameIndexInTheTwoArrays() throws Exception {
        Map<String, Object> envelope =
                apply(List.of(pos("ghost-1", 1, 1)), List.of(conn("ghost-c1")));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals(0, failed.get(0).get("index"));
        assertEquals(0, failed.get(1).get("index"));
        assertNotEquals("two rows at index 0 must still be tellable apart",
                failed.get(0).get("array"), failed.get(1).get("array"));
    }

    // ---------- nothing is applied ----------

    /**
     * The all-or-nothing contract, asserted on the model rather than read off the code. A payload
     * whose first entry is perfectly valid must still leave that object where it was.
     */
    @Test
    public void shouldMoveNothing_whenAnyEntryFails() throws Exception {
        IDiagramModelObject before = findObject("vo-a");
        int x = before.getBounds().getX();
        int y = before.getBounds().getY();

        apply(List.of(pos("vo-a", 999, 888), pos("ghost-1", 10, 10)), null);

        IDiagramModelObject after = findObject("vo-a");
        assertEquals("a valid entry beside a failing one must not land", x, after.getBounds().getX());
        assertEquals(y, after.getBounds().getY());
        assertEquals("nothing may be dispatched", 0, executedCommands);
    }

    /**
     * The throw precedes the approval gate, so this story opens no proposal arm and owes no
     * deferred-arm disclosure. Asserted by running it under approval mode, not by reading the
     * order of two statements — a sibling tool was found re-deriving at execute precisely because
     * someone read instead of ran.
     */
    @Test
    public void shouldRefuseRatherThanPropose_whenApprovalModeIsOn() throws Exception {
        approvalMode = true;

        Map<String, Object> envelope =
                apply(List.of(pos("ghost-1", 10, 10), pos("ghost-2", 20, 20)), null);

        assertEquals("the refusal must not become a proposal", 2, failedRows(envelope).size());
        assertFalse("no proposal may be stored", envelope.containsKey("result"));
        assertEquals("nothing may be queued for a human to approve", 0, executedCommands);
    }

    // ---------- the envelope this refusal now uses ----------

    /**
     * A deliberate, disclosed widening: carrying a typed array at all requires the envelope that
     * can hold one, and that envelope also emits {@code nextSteps} and {@code _meta.modelVersion},
     * which this tool's refusals did not have. Pinned rather than left for a reviewer to find.
     */
    @Test
    public void shouldPublishNextStepsAndModelVersion_onEveryRefusalItNowBuilds() throws Exception {
        Map<String, Object> many =
                apply(List.of(pos("ghost-1", 1, 1), pos("ghost-2", 2, 2)), null);
        assertTrue("a multi-entry refusal guides the agent", many.containsKey("nextSteps"));
        assertNotNull(meta(many).get("modelVersion"));

        Map<String, Object> one = apply(List.of(pos("ghost-1", 1, 1)), null);
        assertTrue("the single-entry refusal takes the same path, and gains the same envelope",
                one.containsKey("nextSteps"));
        assertNotNull(meta(one).get("modelVersion"));
    }

    /** The closing entry states what happened to the entries that were fine. */
    @Test
    public void shouldCloseNextStepsBySayingNothingWasApplied() throws Exception {
        List<String> steps = nextSteps(
                apply(List.of(pos("vo-a", 1, 1), pos("ghost-1", 2, 2)), null));
        assertTrue("the last entry must say nothing landed",
                steps.get(steps.size() - 1).startsWith("Nothing was applied"));
    }

    // ---------- the guards that run before either walk ----------

    /**
     * The view is resolved before any entry is examined, so a call naming a dead view reports that
     * and not a position failure — even when it also carries one.
     */
    @Test
    public void shouldReportViewNotFound_whenTheViewIsAlsoAbsent() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "no-such-view");
        args.put("positions", List.of(pos("ghost-1", 1, 1)));
        Map<String, Object> error = error(invokeTool(registryOverLiveAccessor(),
                "apply-positions", args));

        assertEquals("VIEW_NOT_FOUND", error.get("code"));
        assertFalse("collection must not have moved ahead of the view guard",
                error.containsKey("failed"));
    }

    /**
     * The entry-count guard also runs first. Were collection moved ahead of it, this payload would
     * build ten thousand and one commands before refusing.
     */
    @Test
    public void shouldRefuseOnCount_beforeAnyEntryIsPrepared() throws Exception {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 10001; i++) {
            tooMany.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> error = error(apply(tooMany, null));

        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue("the count guard, not a per-entry failure, must answer",
                ((String) error.get("message")).contains("exceeds maximum"));
        assertFalse(error.containsKey("failed"));
    }

    // ---------- the cap, and the counts it publishes ----------

    /**
     * Past the cap. Every number the refusal states is the real one: the count clause names how
     * many failed and how many are listed, and the closing guidance names the true remainder rather
     * than one computed from the truncated list.
     */
    @Test
    public void shouldCapTheRows_andStillPublishTheTrueTotal() throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> envelope = apply(ghosts, null);
        Map<String, Object> error = error(envelope);

        assertEquals("the rows stop at the cap", 50, failedRows(envelope).size());
        assertTrue("the clause must state the true total: " + error.get("message"),
                ((String) error.get("message")).contains("60 entries failed validation"));
        assertTrue("and must not promise every one is listed: " + error.get("message"),
                ((String) error.get("message")).contains("the first 50 are listed."));

        List<String> steps = nextSteps(envelope);
        assertTrue("the remainder must be the real remainder: " + steps,
                steps.stream().anyMatch(step -> step.startsWith("50 further failed entries")
                        && step.endsWith("the first 50 of 60")));
    }

    /**
     * The refusal's size is bounded by the cap, not by the payload. A green row suite cannot see
     * response size, and the sibling tool's live gate found a refusal republishing a
     * twenty-eight-tool list once per failure — bounded rows are the only thing standing between
     * this tool's ten-thousand-entry ceiling and the same defect at a hundred times the scale.
     *
     * <p>Measured at the cap. The refusal used to be about 36 KB, most of it one guidance sentence
     * repeated once per row; it is now about 13 KB, because the rows reference that sentence
     * instead of each carrying it. What must never regress is the bound itself.</p>
     *
     * <p><b>The budget below tracks the sentence being carried once, not how long the sentence
     * is.</b> While every row carried its own copy, fifty rows multiplied every added character
     * by fifty and the budget had roughly four thousand bytes of headroom — one ordinary sentence
     * of advice away from red, on a change that had nothing to do with the cap. Lengthening the
     * advice now costs its own length once. So a failure here is not an invitation to raise the
     * number: it means either the shared sentence has grown enormously, or the rows have gone
     * back to repeating it, and the second is what this figure exists to catch.</p>
     */
    @Test
    public void shouldBoundTheRefusalBytesByTheCap_notByThePayload() throws Exception {
        int atCap = refusalBytes(50);
        int wayPast = refusalBytes(9000);

        assertTrue("nine thousand failing entries must not cost more than the cap allows: "
                + wayPast + " vs " + atCap, wayPast < atCap + 2000);
        assertTrue("the capped refusal must stay near the size a refusal carrying one copy of the "
                + "shared advice costs; a jump back towards the thirty-six thousand bytes fifty "
                + "separate copies cost means the rows are repeating it again: " + wayPast,
                wayPast < 16_000);
    }

    /**
     * The advice every stale entry earns is carried once for the refusal, not once per entry.
     *
     * <p>An agent replaying a saved layout onto a view that was cleared and rebuilt has every id
     * stale, so this is the ordinary failure rather than the pathological one: at the cap, fifty
     * rows used to carry fifty byte-identical copies of one four-hundred-and-seventy-seven
     * character sentence, which was most of the refusal. The rows still name every entry the
     * caller has to fix — what changed is that the sentence they all share is published once.</p>
     */
    @Test
    public void shouldCarryTheRepeatedAdviceOnce_whenEveryEntryIsStale() throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> envelope = apply(ghosts, null);

        assertEquals("every entry the cap allows must still be named", 50,
                failedRows(envelope).size());

        String wire = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(envelope);
        int copies = 0;
        for (int at = wire.indexOf("back-reference THAT operation instead"); at >= 0;
                at = wire.indexOf("back-reference THAT operation instead", at + 1)) {
            copies++;
        }
        assertEquals("the sentence belongs exactly once on this tool — in the dictionary the rows "
                + "reference — and nowhere else: this refusal carries no top-level correction at "
                + "all, deliberately, so there is no second legitimate copy. More than one means "
                + "the rows are repeating it again; fewer means the dictionary they reference is "
                + "gone: " + copies,
                1, copies);

        for (Map<String, Object> row : failedRows(envelope)) {
            assertTrue("and every row must name the sentence it no longer carries: " + row,
                    row.containsKey("correctionRef"));
        }
        assertReferencesAndDictionaryAgree(envelope);
    }

    /**
     * The dictionary and the rows cannot disagree in either direction: every reference a row names
     * resolves here, and the refusal carries no entry no row names.
     *
     * <p>Asserted on this tool as well as its sibling because the two publish their rows from
     * different builders, and a refusal that emits the rows without the dictionary beside them
     * hands the caller a reference into nothing. A count of how often a sentence appears cannot
     * see that: dropping the dictionary makes the sentence appear <em>less</em> often, which is
     * what a pin counting copies is asking for. The failure has to be looked for directly.</p>
     */
    @Test
    public void shouldResolveEveryReference_andNameEveryDictionaryEntry() throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        assertReferencesAndDictionaryAgree(apply(ghosts, List.of(conn("gc-1"), conn("gc-2"))));
    }

    /** Neither a reference into nothing, nor an entry the caller pays for and cannot use. */
    private static void assertReferencesAndDictionaryAgree(Map<String, Object> envelope) {
        Map<String, Object> error = error(envelope);
        for (String pair : List.of("messageRef:messages", "correctionRef:corrections")) {
            String refKey = pair.split(":")[0];
            String dictKey = pair.split(":")[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> dictionary = (Map<String, Object>) error.get(dictKey);
            java.util.Set<String> named = new java.util.LinkedHashSet<>();
            for (Map<String, Object> row : failedRows(envelope)) {
                if (row.containsKey(refKey)) {
                    assertNotNull("a row names " + refKey + "=" + row.get(refKey)
                            + " and no " + dictKey + " is published beside it", dictionary);
                    assertTrue("a row names " + refKey + "=" + row.get(refKey) + " which "
                            + dictKey + " does not resolve: " + dictionary,
                            dictionary.containsKey(row.get(refKey)));
                    named.add((String) row.get(refKey));
                }
            }
            if (dictionary != null) {
                assertEquals(dictKey + " must carry no entry no row names",
                        named, dictionary.keySet());
            }
        }
    }

    private int refusalBytes(int failingEntries) throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < failingEntries; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "view-1");
        args.put("positions", ghosts);
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec =
                registryOverLiveAccessor().getToolSpecifications().stream()
                        .filter(x -> x.tool().name().equals("apply-positions"))
                        .findFirst().orElseThrow();
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                        "apply-positions", args));
        return ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0))
                .text().getBytes("UTF-8").length;
    }

    /**
     * The cap is shared across both caller arrays and the walks run in a fixed order, so an array
     * that fails wholesale takes every row slot. Reporting only a total there would send a caller
     * off to fix fifty positions, resend, and only then discover its connections were broken too —
     * one round-trip per array, which is the cost this refusal exists to remove.
     */
    @Test
    public void shouldNameAnArrayWhoseFailuresTheCapCannotList() throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> envelope =
                apply(ghosts, List.of(conn("gc-1"), conn("gc-2"), conn("gc-3")));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("the positions failures alone fill the cap", 50, failed.size());
        assertTrue("so no connections row can fit — that is the trap being guarded",
                failed.stream().noneMatch(row -> "connections".equals(row.get("array"))));

        assertTrue("the refusal must still say the connections array failed: " + nextSteps(envelope),
                nextSteps(envelope).stream().anyMatch(step ->
                        step.contains("does not list every failure")
                                && step.contains("3 in 'connections'")));
    }

    /** The complementary arm: nothing is hidden, so nothing may claim anything is. */
    @Test
    public void shouldNotClaimAnythingIsUnlisted_whenEveryFailureFits() throws Exception {
        List<String> steps = nextSteps(
                apply(List.of(pos("ghost-1", 1, 1)), List.of(conn("gc-1"))));

        assertTrue("both failures are listed, so the unlisted clause must be absent: " + steps,
                steps.stream().noneMatch(step -> step.contains("does not list every failure")));
    }

    /**
     * The ONE message class that deliberately changes: a failure naming something inside a
     * readable entry. The bendpoint helpers are shared with the single-connection tools and report
     * a bendpoint index alone, which read from inside an array leaves a caller told that some
     * bendpoint is malformed and never which connection carried it.
     */
    @Test
    public void shouldPrefixAFieldFailureWithItsEntry_evenWhenItIsTheOnlyOne() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("viewConnectionId", connectionId);
        bad.put("bendpoints", List.of(Map.of("startX", 1, "startY", 2, "endX", 3)));

        Map<String, Object> error = error(apply(null, List.of(bad)));

        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue("the message must name the entry: " + error.get("message"),
                ((String) error.get("message")).startsWith("connections[0]: Bendpoint[0]"));
    }

    /**
     * And the class that must NOT change. A missing or blank id already reads as a parameter fault
     * and gains nothing from an entry prefix, so a caller sending one bad entry must read exactly
     * what it read before this change. Asserted on the text, because a code-only check cannot see
     * a message drifting.
     */
    @Test
    public void shouldLeaveAMissingIdMessageExactlyAsItWas_whenItIsTheOnlyFailure()
            throws Exception {
        Map<String, Object> positions =
                error(apply(List.of(Map.of("viewObjectId", "   ")), null));
        assertEquals("INVALID_PARAMETER", positions.get("code"));
        assertEquals("Missing or empty required parameter: viewObjectId",
                positions.get("message"));

        Map<String, Object> connections =
                error(apply(null, List.of(Map.of("viewConnectionId", "   "))));
        assertEquals("Missing or empty required parameter: viewConnectionId",
                connections.get("message"));
    }

    /**
     * At exactly the cap nothing is excluded, so the sentence that must appear past it must not
     * appear here. A boundary pin and an arm pin are complementary: this one is what makes the
     * other one's mutation meaningful.
     */
    @Test
    public void shouldListEveryRow_atExactlyTheCap() throws Exception {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ghosts.add(pos("ghost-" + i, i, i));
        }
        Map<String, Object> envelope = apply(ghosts, null);

        assertEquals(50, failedRows(envelope).size());
        assertTrue("nothing was excluded, so the promise is true here",
                ((String) error(envelope).get("message")).contains("every one is listed."));
        assertTrue("and the remainder points at the whole array",
                nextSteps(envelope).stream().anyMatch(step -> step.endsWith("all 50")));
    }

    // ---------- the handler stage reads its arrays the same way ----------

    /**
     * A programmatically generated payload carries one systematic shape error in every entry at
     * once, which is the case a first-failure-only refusal serves worst.
     */
    @Test
    public void shouldReportEveryMalformedPositionEntry() throws Exception {
        Map<String, Object> envelope =
                apply(List.of("not-an-object", "also-not", "third-not"), null);

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals("all three malformed entries must be reported", 3, failed.size());
        assertEquals("INVALID_PARAMETER", failed.get(0).get("errorCode"));
        assertEquals(2, failed.get(2).get("index"));
    }

    /**
     * The layer boundary does not stop at the array boundary: a malformed positions entry must not
     * hide a malformed connections entry, even though neither array ever reaches the accessor.
     */
    @Test
    public void shouldReportMalformedEntriesFromBothArrays() throws Exception {
        Map<String, Object> envelope = apply(
                List.of(pos("vo-a", 5, 5), "malformed"), List.of("malformed-conn"));

        List<Map<String, Object>> failed = failedRows(envelope);
        assertEquals(2, failed.size());
        assertEquals("positions", failed.get(0).get("array"));
        assertEquals(1, failed.get(0).get("index"));
        assertEquals("connections", failed.get(1).get("array"));
        assertEquals(0, failed.get(1).get("index"));
    }

    /** One malformed entry still reads exactly as it did, code and message and correction. */
    @Test
    public void shouldKeepTodaysMessage_whenExactlyOneEntryIsMalformed() throws Exception {
        Map<String, Object> error = error(apply(List.of("not-an-object"), null));

        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertEquals("positions[0] must be an object with viewObjectId", error.get("message"));
        assertEquals("Each position entry must be an object with viewObjectId and "
                + "at least one of x, y, width, height", error.get("suggestedCorrection"));
        assertFalse(error.containsKey("failed"));
    }

    /**
     * The blind spot the collection closes for free. The bendpoint helpers are shared with the
     * single-connection tools and name a bendpoint index alone; read from inside an array that
     * leaves a caller told some bendpoint is malformed and not which connection carried it.
     */
    @Test
    public void shouldNameTheConnectionEntry_whenOneOfItsBendpointsIsMalformed() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("viewConnectionId", connectionId);
        bad.put("bendpoints", List.of(Map.of("startX", 1, "startY", 2, "endX", 3)));

        Map<String, Object> error = error(apply(null, List.of(conn(connectionId), bad)));

        assertTrue("the message must name the connection entry, not only the bendpoint: "
                + error.get("message"),
                ((String) error.get("message")).startsWith("connections[1]: Bendpoint[0]"));
    }

    /**
     * The record constructors reject a blank id with an {@code IllegalArgumentException} that would
     * surface as an internal error. They are unreachable because the parameter check collects
     * first, and are kept as the backstop they are rather than deleted — this pins that the
     * reachable path answers, so a regression shows up as a changed code rather than a crash.
     */
    @Test
    public void shouldRejectABlankId_asAParameterFault_notAnInternalError() throws Exception {
        Map<String, Object> error = error(apply(List.of(Map.of("viewObjectId", "   ")), null));

        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---------- helpers that read a response ----------

    private IDiagramModelObject findObject(String id) {
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelObject obj && id.equals(obj.getId())) {
                return obj;
            }
        }
        throw new AssertionError("no view object " + id);
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
    private static Map<String, Object> meta(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("_meta");
    }

    @SuppressWarnings("unchecked")
    private static List<String> nextSteps(Map<String, Object> envelope) {
        Object steps = envelope.get("nextSteps");
        if (!(steps instanceof List<?>)) {
            throw new AssertionError("expected nextSteps, got: " + envelope);
        }
        return (List<String>) steps;
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
