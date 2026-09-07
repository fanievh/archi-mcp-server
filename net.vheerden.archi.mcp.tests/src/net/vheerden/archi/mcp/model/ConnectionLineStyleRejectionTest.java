package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a {@code lineStyle} handed to a <em>connection</em> tool is refused on every path that
 * reaches a connection, and that no line-style write is ever made to an ArchiMate connection.
 *
 * <h2>Why a rejection rather than a silent drop</h2>
 *
 * <p>An ArchiMate connection has no settable line style. The platform's connection figures hardcode
 * their dash pattern per relationship type and never read it from the model; the metamodel carries
 * no connection line-style attribute at all; and the platform's own property panel and format
 * painter both positively exclude ArchiMate connections from the line-type control. A
 * {@code lineStyle} passed to one of these tools therefore cannot change anything, ever.</p>
 *
 * <p>Before this seam existed the parameter was parsed, was counted as a requested change, was
 * dropped without a word, and the tool answered {@code success}. The caller is an agent that cannot
 * see the canvas, so that {@code success} was its only ground truth — it would go on to build a
 * diagram on a distinction the model does not carry. A confident wrong answer is worse than an
 * error precisely because the failure surfaces far downstream and unattributably.</p>
 *
 * <h2>Why every path is enumerated here</h2>
 *
 * <p>The check lives at one seam ({@code StylingHelper.validateConnectionStylingParams}), which is
 * only worth anything if every connection-styling path actually reaches that seam. A guard an
 * alternate path routes around is not a guard. Each path below is asserted, not assumed —
 * standalone, both bulk branches, and the same-batch back-reference branch that hands a
 * still-detached connection over directly instead of looking it up by id.</p>
 */
public class ConnectionLineStyleRejectionTest {

    private static final String SESSION = "connection-line-style-session";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateRelationship relationship;
    private IArchimateRelationship undrawnFlow;
    private IDiagramModelArchimateObject sourceViewObject;
    private IDiagramModelArchimateObject targetViewObject;
    private IDiagramModelArchimateObject undrawnSourceViewObject;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Connection Line Style Fixture");
        model.setId("model-line-style");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Flows");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor source = factory.createBusinessActor();
        source.setId("actor-1");
        source.setName("Caller");
        business.getElements().add(source);

        IBusinessActor target = factory.createBusinessActor();
        target.setId("actor-2");
        target.setName("Callee");
        business.getElements().add(target);

        // On the view, but with its relationship deliberately NOT yet drawn, so the
        // add-connection-to-view paths have something to create rather than tripping the
        // already-on-view precondition.
        IBusinessActor third = factory.createBusinessActor();
        third.setId("actor-3");
        third.setName("Second Caller");
        business.getElements().add(third);

        // Deliberately NOT on the view: the back-reference test places it mid-call.
        IBusinessActor unplaced = factory.createBusinessActor();
        unplaced.setId("actor-4");
        unplaced.setName("Late Caller");
        business.getElements().add(unplaced);

        relationship = factory.createFlowRelationship();
        relationship.setId("rel-1");
        relationship.setSource(source);
        relationship.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        undrawnFlow = factory.createFlowRelationship();
        undrawnFlow.setId("rel-2");
        undrawnFlow.setSource(third);
        undrawnFlow.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(undrawnFlow);

        IArchimateRelationship lateFlow = factory.createFlowRelationship();
        lateFlow.setId("rel-3");
        lateFlow.setSource(unplaced);
        lateFlow.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(lateFlow);

        sourceViewObject = factory.createDiagramModelArchimateObject();
        sourceViewObject.setId("vo-1");
        sourceViewObject.setArchimateElement(source);
        sourceViewObject.setBounds(40, 40, 120, 55);
        view.getChildren().add(sourceViewObject);

        targetViewObject = factory.createDiagramModelArchimateObject();
        targetViewObject.setId("vo-2");
        targetViewObject.setArchimateElement(target);
        targetViewObject.setBounds(320, 40, 120, 55);
        view.getChildren().add(targetViewObject);

        undrawnSourceViewObject = factory.createDiagramModelArchimateObject();
        undrawnSourceViewObject.setId("vo-3");
        undrawnSourceViewObject.setArchimateElement(third);
        undrawnSourceViewObject.setBounds(40, 140, 120, 55);
        view.getChildren().add(undrawnSourceViewObject);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);
        connection.connect(sourceViewObject, targetViewObject);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
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
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- Path 1: auto-connect-view (standalone) -----------------------------------------------

    @Test
    public void shouldRejectLineStyle_whenAutoConnectViewIsAskedToStyleTheConnectionsItDraws() {
        ModelAccessException e = captureFrom(() -> accessor.autoConnectView(
                SESSION, view.getId(), null, null, null, null, dashed()));
        assertRejection(e);
    }

    // ---- Path 2: add-connection-to-view (standalone) ------------------------------------------

    @Test
    public void shouldRejectLineStyle_whenAddConnectionToViewIsCalledStandalone() {
        ModelAccessException e = captureFrom(() -> accessor.addConnectionToView(
                SESSION, view.getId(), undrawnFlow.getId(),
                undrawnSourceViewObject.getId(), targetViewObject.getId(),
                null, null, dashed(), null, null));
        assertRejection(e);
    }

    // ---- Path 3: update-view-connection (standalone) ------------------------------------------

    @Test
    public void shouldRejectLineStyle_whenUpdateViewConnectionIsCalledStandalone() {
        ModelAccessException e = captureFrom(() -> accessor.updateViewConnection(
                SESSION, connection.getId(), null, null, dashed(), null, null));
        assertRejection(e);
    }

    // ---- Path 4: bulk update-view-connection ---------------------------------------------------

    @Test
    public void shouldRejectLineStyle_whenUpdateViewConnectionRunsInsideBulk() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-connection", params(
                        "viewConnectionId", connection.getId(),
                        "lineStyle", "dashed"))),
                "style a connection in bulk", true);
        assertBulkRejection(result, 0);
    }

    // ---- Path 5: bulk add-connection-to-view ---------------------------------------------------

    @Test
    public void shouldRejectLineStyle_whenAddConnectionToViewRunsInsideBulk() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-connection-to-view", params(
                        "viewId", view.getId(),
                        "relationshipId", undrawnFlow.getId(),
                        "sourceViewObjectId", undrawnSourceViewObject.getId(),
                        "targetViewObjectId", targetViewObject.getId(),
                        "lineStyle", "dashed"))),
                "add a styled connection in bulk", true);
        assertBulkRejection(result, 0);
    }

    // ---- Path 6: the same-batch back-reference branch ------------------------------------------

    /**
     * The back-reference branch hands a still-detached view object created earlier in the same
     * call straight to the direct prepare, bypassing the id lookup the other branches use. It is a
     * separate prepare, so it needs its own proof rather than an argument by analogy.
     */
    @Test
    public void shouldRejectLineStyle_whenTheConnectionTargetsAViewObjectMadeInTheSameBulkCall() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", params(
                        "viewId", view.getId(),
                        "elementId", "actor-4",
                        "x", 40, "y", 220)),
                new BulkOperation("add-connection-to-view", params(
                        "viewId", view.getId(),
                        "relationshipId", "rel-3",
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", targetViewObject.getId(),
                        "lineStyle", "dashed"))),
                "place an element then connect it by back-reference", true);

        assertTrue("the back-referenced placement itself must succeed, otherwise this test is not "
                + "exercising the direct-prepare branch at all",
                result.operations().stream().anyMatch(o -> "add-to-view".equals(o.tool())));
        assertBulkRejection(result, 1);
    }

    // ---- Value-independence and whole-call rejection, end to end -------------------------------

    /**
     * "banana" and "dashed" must be refused identically through a real tool call, not merely at
     * the validator. A caller that gets an enum error for one and success for the other would
     * reasonably conclude the other had been applied.
     */
    @Test
    public void shouldRejectLineStyleIdenticallyThroughTheTool_forNonsenseAndForAValidEnum() {
        ModelAccessException valid = captureFrom(() -> accessor.updateViewConnection(
                SESSION, connection.getId(), null, null, lineStyle("dashed"), null, null));
        ModelAccessException nonsense = captureFrom(() -> accessor.updateViewConnection(
                SESSION, connection.getId(), null, null, lineStyle("banana"), null, null));

        assertEquals(valid.getErrorCode(), nonsense.getErrorCode());
        assertEquals(valid.getMessage(), nonsense.getMessage());
    }

    /** No partial apply: the supported fields beside it must not land. */
    @Test
    public void shouldLeaveTheConnectionUntouched_whenLineStyleRidesAlongsideSupportedStyling() {
        String colorBefore = connection.getLineColor();
        int widthBefore = connection.getLineWidth();

        captureFrom(() -> accessor.updateViewConnection(SESSION, connection.getId(), null, null,
                new StylingParams(null, "#D35400", null, null, 2, null, null, null,
                        null, null, null, "dashed", null, null, null, null),
                null, null));

        assertEquals("lineColor must not be applied when the call is rejected",
                colorBefore, connection.getLineColor());
        assertEquals("lineWidth must not be applied when the call is rejected",
                widthBefore, connection.getLineWidth());
    }

    // ---- Negative control: the supported rail still works --------------------------------------

    /**
     * Runs the real path. A guard that refused all connection styling would satisfy every
     * assertion above; this proves the idiom the rejection recommends actually succeeds, and that
     * it is applied to the model rather than merely echoed.
     */
    @Test
    public void shouldApplyTheRecommendedIdiom_whenLineColorAndLineWidthAreUsedInstead() {
        assertNotNull(accessor.updateViewConnection(SESSION, connection.getId(), null, null,
                new StylingParams(null, "#D35400", null, null, 2), null, null));

        assertEquals("#D35400", connection.getLineColor());
        assertEquals(2, connection.getLineWidth());
    }

    // ---- The write that must never appear (negative control, source level) ---------------------

    /**
     * A line style stored on an ArchiMate connection would persist into the saved file, render
     * nowhere, and survive round-trips as a phantom the agent has no way to detect. This pin fails
     * if a future change "helpfully" adds that write back.
     *
     * <p>The pin is scoped by receiver and by the line-bit constants rather than by the bare token
     * {@code setType(}: {@code StylingHelper} legitimately calls {@code setType(int)} on a view
     * <em>object</em> (the alternate-figure selector for a Grouping element), which shares only a
     * method name with the connection API. A bare-token scan would go red on correct code.</p>
     */
    @Test
    public void shouldNeverWriteALineTypeToAConnection_inAnyOfTheConnectionStylingSources() {
        List<String> offenders = new ArrayList<>();
        for (String relative : List.of(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/StylingHelper.java",
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/UpdateViewConnectionCommand.java",
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/AddConnectionToViewCommand.java",
                // The facade applies connection lineColor/fontColor/lineWidth INLINE, in
                // prepareAddConnectionToView and prepareAddConnectionToViewDirect, right beside a
                // delegation to applyConnectionStyling. That block is the most natural place for a
                // future change to "helpfully" restore the line-type write, so a pin that does not
                // read this file leaves its likeliest entry point unwatched. Scannable because the
                // legal setType calls it does contain are allow-listed above by exact text.
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java")) {
            String source = readRepoFile(relative);
            offenders.addAll(lineTypeWritesIn(relative, source));
        }
        assertTrue("A connection line-type write appeared in the connection-styling sources. The "
                + "platform never renders it, so it would be a phantom in the saved model:\n  "
                + String.join("\n  ", offenders), offenders.isEmpty());
    }

    /**
     * The pin above is only worth trusting if it has been seen to fail. A pin that has never gone
     * red is an assumption wearing a test's clothing — so this runs the same detector over a
     * deliberately-planted violation of each shape it is meant to catch, in memory.
     */
    @Test
    public void shouldDetectAPlantedLineTypeWrite_soThePinIsKnownToBeAbleToFail() {
        assertFalse("must catch a write on a connection-named receiver",
                lineTypeWritesIn("planted", "        conn.setType(2);").isEmpty());
        assertFalse("must catch a write on a differently-named connection receiver",
                lineTypeWritesIn("planted", "        connection.setType(bits);").isEmpty());
        assertFalse("must catch the bitmask constants even without a visible receiver",
                lineTypeWritesIn("planted",
                        "        int bits = IDiagramModelConnection.LINE_DASHED;").isEmpty());
        assertFalse("must catch the dotted constant too",
                lineTypeWritesIn("planted", "        x |= LINE_DOTTED;").isEmpty());

        // The shapes the earlier receiver-name form of this pin walked straight past. A detector
        // is only worth its name against the evasion nobody planted, so these are planted.
        assertFalse("must catch a receiver whose name says nothing about connections",
                lineTypeWritesIn("planted", "        edge.setType(1);").isEmpty());
        assertFalse("must catch a raw bit literal with no named constant to give it away",
                lineTypeWritesIn("planted", "        c.setType(2);").isEmpty());
        assertFalse("must catch a chained call, which has no bare receiver identifier at all",
                lineTypeWritesIn("planted", "        getConnection().setType(2);").isEmpty());
        assertFalse("must catch a self-call",
                lineTypeWritesIn("planted", "        this.setType(2);").isEmpty());
        assertFalse("must catch whitespace between the receiver and the call",
                lineTypeWritesIn("planted", "        conn . setType ( 2 );").isEmpty());

        // ...and must NOT fire on any of the legal calls that ship today, nor on prose that
        // merely names the API. Without these the pin above could be satisfied by refusing
        // everything, which would be a broken build rather than a guard.
        assertTrue("must not fire on the Grouping alternate-figure selector",
                lineTypeWritesIn("clean", "            archi.setType(figureInt);").isEmpty());
        assertTrue("must not fire on clone-view copying a plain connection's honoured line type",
                lineTypeWritesIn("clean", "        target.setType(source.getType());").isEmpty());
        assertTrue("must not fire on clone-view copying a view object's figure",
                lineTypeWritesIn("clean", "            cloned.setType(archObj.getType());").isEmpty());
        assertTrue("must not fire on the folder-kind setter, an unrelated API",
                lineTypeWritesIn("clean", "        newFolder.setType(FolderType.USER);").isEmpty());
        assertTrue("must not fire on a comment naming the connection API",
                lineTypeWritesIn("clean",
                        "     * `IDiagramModelConnection.setType()` LINE_DASHED bits do not "
                        + "drive rendering.").isEmpty());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * The {@code setType(int)} calls that are legal in the scanned files, matched on their exact
     * source text.
     *
     * <p>An allow-list rather than a receiver-name pattern, deliberately. The earlier form of this
     * pin only flagged {@code setType(} when the receiver's name contained "conn", which a write
     * through any other identifier — {@code edge.setType(1)}, {@code getConnection().setType(1)} —
     * walked straight past. Every {@code setType(} is now an offender unless it is one of these
     * exact lines, so a new one fails closed. Renaming a variable on one of these lines will also
     * fail it: that is the intended cost, and it costs one line of review.</p>
     *
     * <p>Each is legal for a different reason. The first two are the alternate-<em>figure</em>
     * selector on a view object ({@code IDiagramModelArchimateObject.setType(int)}), which shares
     * nothing with the connection API but its method name. The third is clone-view copying an
     * existing hand-drawn <em>plain</em> connection, the one kind whose line style the platform
     * does honour. The fourth is a folder kind.</p>
     */
    private static final List<String> LEGAL_SET_TYPE_CALLS = List.of(
            "archi.setType(figureInt);",                 // Grouping alternate figure (view object)
            "cloned.setType(archObj.getType());",        // clone-view: view-object figure
            "target.setType(source.getType());",         // clone-view: plain connection, honoured
            "newFolder.setType(FolderType.USER);");      // folder kind, unrelated API

    /**
     * Reports connection line-type writes on a single source line. Comment lines are skipped: the
     * shipped tree documents the forbidden API in prose deliberately, and that prose is the reason
     * nobody re-attempts the write.
     */
    private static List<String> lineTypeWritesIn(String where, String source) {
        Pattern anySetType = Pattern.compile("\\.\\s*setType\\s*\\(");
        Pattern lineBitConstant = Pattern.compile("\\bLINE_(DASHED|DOTTED|SOLID)\\b");
        List<String> offenders = new ArrayList<>();
        int number = 0;
        for (String line : source.split("\n", -1)) {
            number++;
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            String bare = line.strip();
            if (anySetType.matcher(line).find() && !LEGAL_SET_TYPE_CALLS.contains(bare)) {
                offenders.add(where + ":" + number + " " + bare);
                continue;
            }
            if (lineBitConstant.matcher(line).find()) {
                offenders.add(where + ":" + number + " " + bare);
            }
        }
        return offenders;
    }

    private static String readRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("Could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the repository checkout; running it from elsewhere would silently "
                + "disable the negative control.");
    }

    private static StylingParams dashed() {
        return lineStyle("dashed");
    }

    private static StylingParams lineStyle(String value) {
        return new StylingParams(null, null, null, null, null, null, null, null,
                null, null, null, value, null, null, null, null);
    }

    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static ModelAccessException captureFrom(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("expected lineStyle on a connection to be rejected, but the call "
                + "returned normally — a success the caller would build on");
    }

    private static void assertRejection(ModelAccessException e) {
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue("message must name the parameter, got: " + e.getMessage(),
                e.getMessage().contains("lineStyle"));
        assertNotNull("a rejection must carry a suggestedCorrection", e.getSuggestedCorrection());
        assertTrue("suggestedCorrection must name the supported idiom, got: "
                        + e.getSuggestedCorrection(),
                e.getSuggestedCorrection().contains("lineColor")
                        && e.getSuggestedCorrection().contains("lineWidth"));
    }

    /**
     * With {@code continueOnError} the bulk path reports the rejection per operation instead of
     * throwing, which lets the assertion name the operation index — proving it was this operation
     * that was refused, not the call as a whole failing for some unrelated reason.
     */
    private static void assertBulkRejection(BulkMutationResult result, int index) {
        assertFalse("the bulk call must not report overall success", result.allSucceeded());
        var failure = result.failedOperations().stream()
                .filter(f -> f.index() == index)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "operation " + index + " must be reported as failed, but the failures were "
                        + result.failedOperations()));
        assertEquals(ErrorCode.INVALID_PARAMETER.name(), failure.errorCode());
        assertTrue("the bulk failure must name lineStyle, got: " + failure.message(),
                failure.message().contains("lineStyle"));
        assertNotNull("the bulk failure must carry the suggestedCorrection through",
                failure.suggestedCorrection());
        assertTrue("the bulk suggestedCorrection must name the supported idiom, got: "
                        + failure.suggestedCorrection(),
                failure.suggestedCorrection().contains("lineColor")
                        && failure.suggestedCorrection().contains("lineWidth"));
    }

    /** Minimal in-memory model manager — the local idiom the sibling accessor tests use. */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
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
