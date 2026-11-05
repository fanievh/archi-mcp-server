package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.PaginationCursor;
import net.vheerden.archi.mcp.response.dto.OpenModelDto;

/**
 * Pins how a tool call's model is resolved: by the address it carries, by the session's open batch,
 * by the session's default, by being the only model open, and otherwise by refusing a write that
 * would be a coin flip.
 *
 * <p>Two real models and two session ids throughout: a one-model fixture cannot see addressing, and a
 * one-session fixture cannot see one session's state leaking into another's.</p>
 */
public class ModelSelectionTest {

    private static final String FIRST = "session-one";
    private static final String SECOND = "session-two";

    private IArchimateModel modelA;
    private IArchimateModel modelB;
    private List<IArchimateModel> open;
    private IArchimateModel gui;
    private IArchimateModel batchModel;
    private final Map<IArchimateModel, Long> epochs = new IdentityHashMap<>();
    private final Set<IArchimateModel> dirty = new HashSet<>();
    private ModelSelection selection;

    @Before
    public void setUp() {
        modelA = newModel("model-a", "Model A");
        modelB = newModel("model-b", "Model B");
        open = new ArrayList<>(List.of(modelA, modelB));
        gui = modelA;
        selection = new ModelSelection(() -> gui, this::epoch, session -> batchModel);
        selection.setOpenModels(() -> open, dirty::contains);
    }

    @After
    public void tearDown() {
        selection.exit();
    }

    private static IArchimateModel newModel(String id, String name) {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setId(id);
        model.setName(name);
        return model;
    }

    private long epoch(IArchimateModel model) {
        return epochs.computeIfAbsent(model, m -> (long) epochs.size() + 1);
    }

    private static ModelAccessException expectRefusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    // ---- no scope: exactly 1.9.0 ------------------------------------------------------------------

    @Test
    public void shouldFollowTheGuiModel_whenNoCallScopeIsOpen() {
        assertSame(modelA, selection.modelForCall(modelA));
        gui = modelB;
        assertSame("with no scope the caller's own fallback is returned untouched",
                modelB, selection.modelForCall(modelB));
        selection.requireUnambiguous("create-element");   // nothing to refuse outside a scope
        assertNull(selection.responseModel());
    }

    // ---- addressed --------------------------------------------------------------------------------

    @Test
    public void shouldResolveTheAddressedModel_whenTheCallNamesAnOpenModel() {
        assertNull(selection.enter(FIRST, "model-b"));

        assertSame(modelB, selection.modelForCall(gui));
        selection.requireUnambiguous("create-element");
        Map<String, Object> meta = selection.responseModel();
        assertEquals("model-b", meta.get("id"));
        assertEquals("Model B", meta.get("name"));
        assertEquals(ModelSelection.ADDRESSED, meta.get("resolution"));
    }

    @Test
    public void shouldRefuseNamingTheModel_whenTheAddressedModelIsNotOpen() {
        ModelAccessException refusal = selection.enter(FIRST, "model-zzz");

        assertNotNull(refusal);
        assertEquals(ErrorCode.MODEL_NOT_OPEN, refusal.getErrorCode());
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("model-zzz"));
        assertTrue("the refusal lists what is open, so the agent can correct the call in one step",
                refusal.getDetails().contains("model-a") && refusal.getDetails().contains("model-b"));
        assertNull("a refused address never falls back to some other model",
                selection.responseModel());
        ModelAccessException again = expectRefusal(() -> selection.modelForCall(gui));
        assertEquals(ErrorCode.MODEL_NOT_OPEN, again.getErrorCode());
    }

    @Test
    public void shouldRefuseListingEachCopysFile_whenTheAddressedIdIsOpenMoreThanOnce() {
        IArchimateModel copy = newModel("model-a", "Model A");
        modelA.setFile(new File("/work/payments.archimate"));
        open.add(copy);   // a copied file keeps its id; this copy is unsaved

        ModelAccessException refusal = selection.enter(FIRST, "model-a");

        assertNotNull(refusal);
        assertEquals(ErrorCode.MODEL_COPIES_AMBIGUOUS, refusal.getErrorCode());
        String text = refusal.getMessage() + " " + refusal.getDetails();
        assertTrue(text, text.contains("/work/payments.archimate"));
        assertTrue("an unsaved copy is named as unsaved", text.contains("unsaved"));
        assertNull(selection.responseModel());
    }

    // ---- sole model -------------------------------------------------------------------------------

    @Test
    public void shouldResolveTheOnlyOpenModel_whenTheCallNamesNone() {
        open.remove(modelB);
        assertNull(selection.enter(FIRST, null));

        assertSame(modelA, selection.modelForCall(gui));
        selection.requireUnambiguous("create-element");
        assertEquals(ModelSelection.SOLE_MODEL, selection.responseModel().get("resolution"));
    }

    // ---- ambiguity --------------------------------------------------------------------------------

    @Test
    public void shouldRefuseAWriteListingTheCandidates_whenSeveralModelsAreOpenAndNoneIsNamed() {
        assertNull("entering never refuses an unaddressed call: a read may follow the GUI",
                selection.enter(FIRST, null));

        ModelAccessException refusal = expectRefusal(() -> selection.requireUnambiguous("create-element"));

        assertEquals(ErrorCode.MODEL_AMBIGUOUS, refusal.getErrorCode());
        String text = refusal.getMessage() + " " + refusal.getDetails();
        assertTrue(text, text.contains("model-a") && text.contains("Model A"));
        assertTrue(text, text.contains("model-b") && text.contains("Model B"));
        assertTrue(text, text.contains("create-element"));
        assertNull("a refused write names no model as the one it acted on", selection.responseModel());
    }

    @Test
    public void shouldFollowTheGuiForAReadLabelledSessionDefault_whenSeveralModelsAreOpenAndNoneIsNamed() {
        gui = modelB;
        selection.enter(FIRST, null);

        assertSame(modelB, selection.modelForCall(gui));
        assertEquals(ModelSelection.SESSION_DEFAULT, selection.responseModel().get("resolution"));
    }

    @Test
    public void shouldUseADistinctCodeForEachRefusal() {
        assertFalse(ErrorCode.MODEL_AMBIGUOUS == ErrorCode.MODEL_NOT_OPEN);
        assertFalse(ErrorCode.MODEL_COPIES_AMBIGUOUS == ErrorCode.MODEL_NOT_OPEN);
        assertFalse(ErrorCode.MODEL_COPIES_AMBIGUOUS == ErrorCode.MODEL_AMBIGUOUS);
    }

    // ---- batch stamp ------------------------------------------------------------------------------

    @Test
    public void shouldResolveTheBatchsModel_whenTheSessionHasAnOpenBatchAndTheCallNamesNone() {
        batchModel = modelB;
        selection.enter(FIRST, null);

        assertSame(modelB, selection.modelForCall(gui));
        selection.requireUnambiguous("create-element");
        assertEquals(ModelSelection.SESSION_DEFAULT, selection.responseModel().get("resolution"));
    }

    // ---- session-default slot ---------------------------------------------------------------------

    @Test
    public void shouldResolveTheSessionDefault_whenTheSessionHasChosenAndTheCallNamesNone() {
        selection.choose(FIRST, "model-b");
        selection.enter(FIRST, null);

        assertSame(modelB, selection.modelForCall(gui));
        selection.requireUnambiguous("create-element");
        assertEquals(ModelSelection.SESSION_DEFAULT, selection.responseModel().get("resolution"));
        selection.exit();

        selection.enter(SECOND, null);
        assertSame("one session's choice never reaches another session", modelA, selection.modelForCall(gui));
    }

    @Test
    public void shouldFailNamingTheChosenModel_whenItIsClosed_andResolveTheReopenedInstance() {
        selection.choose(FIRST, "model-b");
        open.remove(modelB);

        selection.enter(FIRST, null);
        ModelAccessException refusal = expectRefusal(() -> selection.modelForCall(gui));
        assertEquals(ErrorCode.MODEL_NOT_OPEN, refusal.getErrorCode());
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("model-b"));
        selection.exit();

        IArchimateModel reopened = newModel("model-b", "Model B");
        open.add(reopened);
        selection.enter(FIRST, null);
        assertSame("the selection is an id, so the reopened instance answers it", reopened,
                selection.modelForCall(gui));
    }

    @Test
    public void shouldHoldTheSelectionAsAnId_notAModelReference() {
        for (java.lang.reflect.Field field : ModelSelection.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse("ModelSelection." + field.getName() + " holds a model object",
                    IArchimateModel.class.isAssignableFrom(field.getType()));
        }
    }

    // ---- one resolution per call ------------------------------------------------------------------

    @Test
    public void shouldKeepTheCallsModel_whenTheGuiSwitchesMidCall() {
        selection.enter(FIRST, null);
        open.remove(modelB);
        IArchimateModel first = selection.modelForCall(gui);

        open.add(modelB);
        gui = modelB;
        assertSame(first, selection.modelForCall(gui));
    }

    @Test
    public void shouldObserveEveryOpenModel_whenResolving() {
        selection.enter(FIRST, "model-a");
        selection.modelForCall(gui);

        assertTrue(epochs.containsKey(modelA));
        assertTrue("a model open but never active is still observed", epochs.containsKey(modelB));
    }

    @Test
    public void shouldNameNoModel_whenTheCallNeverResolvedOne() {
        selection.enter(FIRST, "model-b");
        assertNull("a call that never touched a model reports none", selection.responseModel());
    }

    @Test
    public void shouldClearTheScope_onExit() {
        selection.enter(FIRST, "model-b");
        selection.exit();
        assertSame(modelA, selection.modelForCall(modelA));
        assertFalse(selection.inScope());
    }

    // ---- the model a pagination cursor names ------------------------------------------------------

    @Test
    public void shouldNameNoCursorModel_whenNoCallScopeIsOpen() {
        assertNull(selection.cursorModel());
    }

    @Test
    public void shouldNameNoCursorModel_whenTheCallsAddressIsRefused() {
        assertNotNull(selection.enter(FIRST, "model-zzz"));

        assertNull(selection.cursorModel());
    }

    @Test
    public void shouldNameTheCallsModelInstance_withoutRecordingThatTheSessionHoldsIt() {
        selection.enter(FIRST, "model-b");

        PaginationCursor.CursorModel named = selection.cursorModel();

        assertEquals("model-b", named.id());
        assertEquals("Model B", named.name());
        assertEquals(String.valueOf(epoch(modelB)), named.key());
        assertNull("naming the model is not acting on it", selection.responseModel());
        assertEquals("no holding is recorded", 0, selection.otherSessionsHolding(SECOND, modelB));
    }

    // ---- binding a nested scope to an instance ----------------------------------------------------

    @Test
    public void shouldResolveTheBoundInstance_andRestoreTheOuterCall() {
        selection.enter(FIRST, "model-a");
        selection.modelForCall(gui);

        ModelSelection.Call outer = selection.bind(FIRST, modelB);
        assertSame(modelB, selection.modelForCall(gui));
        selection.restore(outer);

        assertSame(modelA, selection.modelForCall(gui));
    }

    @Test
    public void shouldBindOnAThreadWithNoScope_andLeaveNoneBehind() {
        ModelSelection.Call outer = selection.bind(FIRST, modelB);
        assertSame(modelB, selection.modelForCall(gui));
        selection.restore(outer);

        assertFalse(selection.inScope());
    }

    // ---- who holds which model --------------------------------------------------------------------

    @Test
    public void shouldCountOtherSessionsHoldingAModel_fromTheirLatestResolution() {
        selection.enter(FIRST, "model-a");
        selection.modelForCall(gui);
        selection.exit();
        selection.enter(SECOND, "model-a");
        selection.modelForCall(gui);
        selection.exit();

        assertEquals(1, selection.otherSessionsHolding(FIRST, modelA));
        assertEquals(1, selection.otherSessionsHolding(SECOND, modelA));

        selection.enter(SECOND, "model-b");
        selection.modelForCall(gui);
        selection.exit();
        assertEquals("a session that has since moved to another model no longer holds this one",
                0, selection.otherSessionsHolding(FIRST, modelA));
    }

    @Test
    public void shouldStopCountingAHolding_fifteenMinutesAfterTheSessionsLastCallOnIt() {
        long[] now = {0L};
        selection.setClock(() -> now[0]);
        selection.enter(SECOND, "model-a");
        selection.modelForCall(gui);
        selection.exit();

        now[0] = java.time.Duration.ofMinutes(14).toNanos();
        assertEquals("still working on it", 1, selection.otherSessionsHolding(FIRST, modelA));
        now[0] = java.time.Duration.ofMinutes(16).toNanos();
        assertEquals("idle for more than 15 minutes: no longer counts", 0,
                selection.otherSessionsHolding(FIRST, modelA));
    }

    @Test
    public void shouldForgetAnEvictedSessionsHolding() {
        selection.enter(SECOND, "model-a");
        selection.modelForCall(gui);
        selection.exit();

        selection.forgetSession(SECOND);

        assertEquals(0, selection.otherSessionsHolding(FIRST, modelA));
    }

    @Test
    public void shouldNotRecordAHolding_onAnInstanceAlreadyClosed() {
        Set<Long> closed = new HashSet<>();
        ModelSelection withClosure = new ModelSelection(() -> gui, this::epoch, session -> null,
                closed::contains);
        withClosure.setOpenModels(() -> open, dirty::contains);
        withClosure.enter(SECOND, "model-a");
        closed.add(epoch(modelA));   // closed after the call resolved it, before it used it

        withClosure.modelForCall(gui);
        withClosure.exit();

        assertEquals(0, withClosure.otherSessionsHolding(FIRST, modelA));
    }

    @Test
    public void shouldDropHoldings_whenTheirModelCloses() {
        selection.enter(SECOND, "model-a");
        selection.modelForCall(gui);
        selection.exit();

        selection.onModelClosed(modelA);
        assertEquals(0, selection.otherSessionsHolding(FIRST, modelA));
    }

    // ---- get-models -------------------------------------------------------------------------------

    @Test
    public void shouldListEveryOpenModel_withFileDirtyAndWhichIsTheCallersOwn() {
        modelA.setFile(new File("/work/a.archimate"));
        dirty.add(modelB);
        gui = modelB;

        List<OpenModelDto> listed = selection.listModels(FIRST);

        assertEquals(2, listed.size());
        OpenModelDto a = listed.get(0);
        OpenModelDto b = listed.get(1);
        assertEquals("model-a", a.id());
        assertEquals("Model A", a.name());
        assertEquals("/work/a.archimate", a.file());
        assertFalse(a.dirty());
        assertNull("an unsaved model has no file", b.file());
        assertTrue(b.dirty());
        assertFalse(a.sessionActive());
        assertTrue("the calling session's unaddressed calls follow the GUI to B", b.sessionActive());
    }

    @Test
    public void shouldMarkTheSessionsChosenModelAsItsOwn_whateverTheGuiShows() {
        selection.choose(FIRST, "model-a");
        gui = modelB;

        List<OpenModelDto> listed = selection.listModels(FIRST);
        assertTrue(listed.get(0).sessionActive());
        assertFalse(listed.get(1).sessionActive());
        assertFalse("another session follows the GUI", selection.listModels(SECOND).get(0).sessionActive());
    }
}
