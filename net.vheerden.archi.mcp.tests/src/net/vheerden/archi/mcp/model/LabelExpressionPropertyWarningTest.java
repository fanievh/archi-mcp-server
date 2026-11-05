package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * A label expression that reads a property the drawn object does not carry is accepted and stored,
 * and Archi then draws that token as an empty string. These pin the warning that says so: on
 * update-view-object on each arm, as a bulk operation, and on the view-wide bulk operation.
 */
public class LabelExpressionPropertyWarningTest {

    private static final String SESSION = "label-session";
    private static final String CODE = StructuredWarningCodes.LABEL_EXPRESSION_PROPERTY_MISSING;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        model = factory.createArchimateModel();
        model.setName("Label Fixture");
        model.setId("model-label");
        model.setDefaults();
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Labelled");
        diagrams.getElements().add(view);
        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Referenced");
        diagrams.getElements().add(otherView);
        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
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

    // ---- fixture helpers ------------------------------------------------------------------------

    private IDiagramModelArchimateObject addElement(String id, String name, String... keys) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId("el-" + id);
        element.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        give(element, keys);
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(element);
        obj.setBounds(10, 10, 120, 55);
        view.getChildren().add(obj);
        return obj;
    }

    private IDiagramModelNote addNote(String id, String... keys) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId(id);
        note.setContent("a note");
        note.setBounds(300, 10, 120, 55);
        give(note, keys);
        view.getChildren().add(note);
        return note;
    }

    private IDiagramModelGroup addGroup(String id, String name) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(name);
        group.setBounds(10, 300, 200, 120);
        view.getChildren().add(group);
        return group;
    }

    private IDiagramModelReference addReference(String id) {
        IDiagramModelReference ref = factory.createDiagramModelReference();
        ref.setId(id);
        ref.setReferencedModel(otherView);
        ref.setBounds(500, 10, 120, 55);
        view.getChildren().add(ref);
        return ref;
    }

    private void give(IProperties holder, String... keys) {
        for (String key : keys) {
            IProperty property = factory.createProperty();
            property.setKey(key);
            property.setValue("v");
            holder.getProperties().add(property);
        }
    }

    private List<StructuredWarningDto> warnings(List<StructuredWarningDto> all) {
        List<StructuredWarningDto> out = new ArrayList<>();
        for (StructuredWarningDto w : all) {
            if (CODE.equals(w.code())) {
                out.add(w);
            }
        }
        return out;
    }

    private List<StructuredWarningDto> update(String viewObjectId, String expression) {
        return warnings(accessor.updateViewObject(SESSION, viewObjectId, null, null, null, null,
                null, null, null, expression).entity().structuredWarnings());
    }

    // ---- which tokens are read --------------------------------------------------------------------

    @Test
    public void propertyKeys_shouldReadOnlyTheUnprefixedForm_inOrder_once() {
        assertEquals(List.of("Owner", "Tier"), LabelExpressionPropertyCheck.propertyKeys(
                "${name} ${property:Owner} $model{property:Ignored} ${property:Tier}"
                        + " ${properties:, :Also} $parent{property:No} ${property:Owner}"));
        assertEquals(List.of("a key with spaces"),
                LabelExpressionPropertyCheck.propertyKeys("${property:a key with spaces}"));
        assertEquals(List.of(), LabelExpressionPropertyCheck.propertyKeys("${name} ${type}"));
    }

    // ---- update-view-object, applied ----------------------------------------------------------------

    @Test
    public void updateViewObject_shouldWarn_whenTheElementLacksTheProperty() {
        addElement("vo-a", "Alpha");

        List<StructuredWarningDto> found = update("vo-a", "${name} ${property:Owner}");

        assertEquals(1, found.size());
        StructuredWarningDto w = found.get(0);
        assertTrue(w.message(), w.message().contains("'Owner'"));
        assertTrue("the applied arm says what is drawn: " + w.message(),
                w.message().contains("draws the token as an empty string"));
        assertTrue("the unchecked forms are named, so silence is not read as a check: " + w.message(),
                w.message().contains("$model{property:KEY}")
                        && w.message().contains("${properties:SEP:KEY}"));
        assertEquals("update-element", w.remediationTool());
        assertEquals(List.of("vo-a"), w.remediationViolatorIds());
    }

    @Test
    public void updateViewObject_shouldStillApplyTheTemplate_whenItWarns() {
        IDiagramModelArchimateObject obj = addElement("vo-a", "Alpha");

        update("vo-a", "${property:Owner}");

        assertEquals("a warning, never a refusal", "${property:Owner}",
                obj.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void updateViewObject_shouldNotWarn_whenTheElementCarriesTheProperty() {
        addElement("vo-a", "Alpha", "Owner");

        assertEquals(List.of(), update("vo-a", "${name} ${property:Owner}"));
    }

    @Test
    public void updateViewObject_shouldWarn_whenOnlyTheCaseDiffers() {
        addElement("vo-a", "Alpha", "owner");

        List<StructuredWarningDto> found = update("vo-a", "${property:Owner}");

        assertEquals("Archi matches keys exactly, case included", 1, found.size());
    }

    @Test
    public void updateViewObject_shouldNameEveryMissingKey_inOneWarning() {
        addElement("vo-a", "Alpha", "Tier");

        List<StructuredWarningDto> found =
                update("vo-a", "${property:Owner} ${property:Tier} ${property:Status}");

        assertEquals(1, found.size());
        String message = found.get(0).message();
        assertTrue(message, message.contains("'Owner'") && message.contains("'Status'"));
        assertFalse("a carried key is not named: " + message, message.contains("'Tier'"));
    }

    @Test
    public void updateViewObject_shouldNotWarn_whenClearingOrWhenNoPropertyIsRead() {
        IDiagramModelArchimateObject obj = addElement("vo-a", "Alpha");
        obj.getFeatures().putString("labelExpression", "${property:Owner}");

        assertEquals("clearing reads nothing", List.of(), update("vo-a", ""));
        assertEquals(List.of(), update("vo-a", "${name} ${type}"));
    }

    @Test
    public void updateViewObject_shouldNotWarn_whenTheLabelExpressionIsNotPartOfTheCall() {
        IDiagramModelArchimateObject obj = addElement("vo-a", "Alpha");
        obj.getFeatures().putString("labelExpression", "${property:Owner}");

        List<StructuredWarningDto> found = warnings(accessor.updateViewObject(SESSION, "vo-a",
                40, null, null, null, null, null, null, null).entity().structuredWarnings());

        assertEquals("the check is of what this call writes", List.of(), found);
    }

    @Test
    public void updateViewObject_shouldReadTheNotesOwnProperties_andNameNoTool() {
        addNote("note-1", "Owner");
        addNote("note-2");

        assertEquals(List.of(), update("note-1", "${property:Owner}"));
        List<StructuredWarningDto> found = update("note-2", "${property:Owner}");
        assertEquals(1, found.size());
        assertNull("no tool here sets a property on a note", found.get(0).remediationTool());
        assertTrue(found.get(0).message(),
                found.get(0).message().contains("Archi's Properties view"));
    }

    @Test
    public void updateViewObject_shouldReadTheReferencedViewsProperties_onAViewReference() {
        addReference("ref-1");

        List<StructuredWarningDto> found = update("ref-1", "${property:Owner}");
        assertEquals(1, found.size());
        assertEquals("update-view", found.get(0).remediationTool());

        give(otherView, "Owner");
        assertEquals("the referenced view's property is the one Archi reads",
                List.of(), update("ref-1", "${property:Owner}"));
    }

    // ---- the deferred arms ----------------------------------------------------------------------------

    @Test
    public void updateViewObject_queued_saysTheKeyIsMissingNow_andMayYetBeAdded() {
        addElement("vo-a", "Alpha");
        dispatcher.beginBatch(SESSION, "queued label");

        MutationResult<ViewObjectDto> result = accessor.updateViewObject(SESSION, "vo-a",
                null, null, null, null, null, null, null, "${property:Owner}");

        assertTrue(result.isBatched());
        List<StructuredWarningDto> found = warnings(result.entity().structuredWarnings());
        assertEquals(1, found.size());
        String message = found.get(0).message();
        assertTrue(message, message.contains("does not carry now")
                && message.contains("queued beside it may still add it"));
        assertFalse("the queued arm makes no applied claim: " + message,
                message.contains(", so Archi draws"));
    }

    @Test
    public void updateViewObject_awaitingApproval_saysTheKeyIsMissingNow() {
        addElement("vo-a", "Alpha");
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<ViewObjectDto> result = accessor.updateViewObject(SESSION, "vo-a",
                null, null, null, null, null, null, null, "${property:Owner}");

        assertTrue(result.isProposal());
        List<StructuredWarningDto> found = warnings(result.entity().structuredWarnings());
        assertEquals(1, found.size());
        assertTrue(found.get(0).message(),
                found.get(0).message().contains("waiting on the human's decision"));
    }

    // ---- bulk -----------------------------------------------------------------------------------------

    @Test
    public void bulkUpdateViewObject_shouldCarryTheWarningOnItsOperation() {
        addElement("vo-a", "Alpha");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "update-view-object",
                Map.of("viewObjectId", "vo-a", "labelExpression", "${property:Owner}"))),
                null, false);

        List<StructuredWarningDto> found = warnings(result.operations().get(0).structuredWarnings());
        assertEquals(1, found.size());
        assertEquals(List.of("vo-a"), found.get(0).remediationViolatorIds());
    }

    @Test
    public void bulk_aPropertyAnEarlierOperationSets_isNotSeen_soTheQueuedWordingIsUsed() {
        // Every operation of a bulk call is prepared before any is applied, so the property an
        // earlier operation adds is not on the element when this one is checked. The warning must
        // therefore say the key is missing NOW and may yet be added, never that it is drawn blank.
        addElement("vo-a", "Alpha");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-element",
                        Map.of("id", "el-vo-a", "properties", Map.of("Owner", "Team A"))),
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", "vo-a", "labelExpression", "${property:Owner}"))),
                null, false);

        List<StructuredWarningDto> found = warnings(result.operations().get(1).structuredWarnings());
        assertEquals(1, found.size());
        assertTrue(found.get(0).message(),
                found.get(0).message().contains("queued beside it may still add it"));
    }

    @Test
    public void setViewLabelExpression_shouldWarnOncePerKey_countingTheObjectsThatLackIt() {
        addElement("vo-a", "Alpha", "Owner");
        addElement("vo-b", "Beta");
        addElement("vo-c", "Gamma", "Tier");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "set-view-label-expression",
                Map.of("viewId", view.getId(),
                        "labelExpression", "${name} ${property:Owner} ${property:Tier}"))),
                null, false);

        List<StructuredWarningDto> found = warnings(result.operations().get(0).structuredWarnings());
        assertEquals("one warning per key: " + found, 2, found.size());
        assertTrue(found.get(0).message(), found.get(0).message().contains("'Owner'")
                && found.get(0).message().contains("2 of the 3 objects"));
        assertEquals(List.of("vo-b", "vo-c"), found.get(0).remediationViolatorIds());
        assertEquals("update-element", found.get(0).remediationTool());
        assertTrue(found.get(1).message(), found.get(1).message().contains("'Tier'"));
        assertEquals(List.of("vo-a", "vo-b"), found.get(1).remediationViolatorIds());
    }

    @Test
    public void setViewLabelExpression_shouldListTenIds_andCountThemAll() {
        for (int i = 0; i < 12; i++) {
            addElement("vo-" + i, "E" + i);
        }

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "set-view-label-expression",
                Map.of("viewId", view.getId(), "labelExpression", "${property:Owner}"))),
                null, false);

        List<StructuredWarningDto> found = warnings(result.operations().get(0).structuredWarnings());
        assertEquals(1, found.size());
        assertEquals(10, found.get(0).remediationViolatorIds().size());
        assertTrue(found.get(0).message(), found.get(0).message().contains("12 of the 12 objects"));
    }

    @Test
    public void setViewLabelExpression_mixedKinds_namesTheToolPerKind() {
        addElement("vo-a", "Alpha");
        addGroup("grp-1", "Group");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "set-view-label-expression",
                Map.of("viewId", view.getId(), "labelExpression", "${property:Owner}",
                        "objectTypes", List.of("element", "group")))),
                null, false);

        List<StructuredWarningDto> found = warnings(result.operations().get(0).structuredWarnings());
        assertEquals(1, found.size());
        assertNull("no single tool reaches both", found.get(0).remediationTool());
        assertTrue(found.get(0).message(), found.get(0).message().contains("update-element sets one"));
    }

    @Test
    public void setViewLabelExpression_shouldWarnNothing_whenClearing() {
        addElement("vo-a", "Alpha");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "set-view-label-expression",
                Map.of("viewId", view.getId(), "labelExpression", ""))),
                null, false);

        assertEquals(List.of(), result.operations().get(0).structuredWarnings());
    }

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

    @Test
    public void aCallerInsideALargerPass_isAnsweredNothing_ratherThanThrown() {
        // A null arm marks a caller inside a larger pass that reports for itself, as for the container warning.
        IDiagramModelArchimateObject box = addElement("nullArmBox", "Box");
        assertTrue(LabelExpressionPropertyCheck.forObject(box, "${property:Missing}", null).isEmpty());
        assertTrue(LabelExpressionPropertyCheck.forObjects(List.of(box), "${property:Missing}", null).isEmpty());
    }
}
