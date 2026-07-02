package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

import net.vheerden.archi.mcp.response.dto.SetViewLabelExpressionResultDto;

/**
 * Tests for {@link SetViewLabelExpressionCommand}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE}. Verifies the
 * view-scoped fan-out: element selection, the no-ghost name guard, the optional type
 * filter, idempotent re-apply, empty-clears, and single-command undo across set/clear.</p>
 */
public class SetViewLabelExpressionCommandTest {

    private static final String FEATURE = "labelExpression";
    private static final String TEMPLATE = "${name} ${property:evidenceMark}";

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
    }

    // ---- helpers ----

    private IDiagramModelArchimateObject addElement(String name) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setArchimateElement(element);
        obj.setBounds(10, 10, 120, 55);
        view.getChildren().add(obj);
        return obj;
    }

    private IDiagramModelGroup addGroup(String name) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName(name);
        view.getChildren().add(group);
        return group;
    }

    private IDiagramModelNote addNote() {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setContent("a note");
        view.getChildren().add(note);
        return note;
    }

    private String labelOf(com.archimatetool.model.IDiagramModelObject obj) {
        return obj.getFeatures().getString(FEATURE, null);
    }

    @SuppressWarnings("unchecked")
    private SetViewLabelExpressionResultDto prepareAndExecute(Map<String, Object> params) {
        PreparedMutation<?> prepared = SetViewLabelExpressionCommand.prepare(model, params);
        prepared.command().execute();
        return (SetViewLabelExpressionResultDto) prepared.entity();
    }

    private Map<String, Object> params(String template) {
        return new java.util.HashMap<>(Map.of("viewId", view.getId(), "labelExpression", template));
    }

    // ---- AC1: apply to all eligible elements ----

    @Test
    public void shouldStampEveryNamedElement_whenExecuted() {
        IDiagramModelArchimateObject a = addElement("Alpha");
        IDiagramModelArchimateObject b = addElement("Beta");

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(TEMPLATE));

        assertEquals(TEMPLATE, labelOf(a));
        assertEquals(TEMPLATE, labelOf(b));
        assertEquals(2, result.appliedCount());
        assertEquals(0, result.skippedCount());
    }

    @Test
    public void shouldStampNestedElement_whenInsideGroup() {
        IDiagramModelGroup group = addGroup("Layer");
        IArchimateElement element = factory.createApplicationComponent();
        element.setName("Nested");
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        IDiagramModelArchimateObject nested = factory.createDiagramModelArchimateObject();
        nested.setArchimateElement(element);
        group.getChildren().add(nested);

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(TEMPLATE));

        assertEquals(TEMPLATE, labelOf(nested));
        assertEquals(1, result.appliedCount());
        // The group itself is visited but not an element type → skipped.
        assertEquals(1, result.skippedCount());
    }

    // ---- AC2: default scope is elements only ----

    @Test
    public void shouldSkipNotesAndGroups_whenDefaultScope() {
        IDiagramModelArchimateObject element = addElement("Alpha");
        IDiagramModelGroup group = addGroup("Named Group");
        IDiagramModelNote note = addNote();

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(TEMPLATE));

        assertEquals(TEMPLATE, labelOf(element));
        assertNull(labelOf(group));
        assertNull(labelOf(note));
        assertEquals(1, result.appliedCount());
        assertEquals(2, result.skippedCount());
    }

    @Test
    public void shouldStampGroup_whenObjectTypesIncludesGroup() {
        IDiagramModelGroup group = addGroup("Named Group");
        Map<String, Object> params = params(TEMPLATE);
        params.put("objectTypes", List.of("element", "group"));

        prepareAndExecute(params);

        assertEquals(TEMPLATE, labelOf(group));
    }

    // ---- AC3: no-ghost name guard ----

    @Test
    public void shouldSkipUnnamedElement_soNoGhostGlyph() {
        IDiagramModelArchimateObject named = addElement("Alpha");
        IDiagramModelArchimateObject unnamed = addElement("");

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(TEMPLATE));

        assertEquals(TEMPLATE, labelOf(named));
        assertNull(labelOf(unnamed));
        assertEquals(1, result.appliedCount());
        assertEquals(1, result.skippedCount());
    }

    // ---- AC4: idempotent re-apply ----

    @Test
    public void shouldBeByteIdenticalAndSameCounts_whenReapplied() {
        IDiagramModelArchimateObject a = addElement("Alpha");

        SetViewLabelExpressionResultDto first = prepareAndExecute(params(TEMPLATE));
        SetViewLabelExpressionResultDto second = prepareAndExecute(params(TEMPLATE));

        assertEquals(TEMPLATE, labelOf(a));
        assertEquals(first.appliedCount(), second.appliedCount());
        assertEquals(first.skippedCount(), second.skippedCount());
    }

    // ---- AC5: empty clears ----

    @Test
    public void shouldClearLabel_whenTemplateEmpty() {
        IDiagramModelArchimateObject a = addElement("Alpha");
        prepareAndExecute(params(TEMPLATE));
        assertEquals(TEMPLATE, labelOf(a));

        prepareAndExecute(params(""));

        assertNull(labelOf(a));
    }

    @Test
    public void shouldThrow_whenLabelExpressionKeyAbsent() {
        IDiagramModelArchimateObject a = addElement("Alpha");
        Map<String, Object> params = new java.util.HashMap<>(Map.of("viewId", view.getId()));
        try {
            SetViewLabelExpressionCommand.prepare(model, params);
            fail("expected ModelAccessException for absent labelExpression");
        } catch (ModelAccessException expected) {
            assertNull(labelOf(a));
        }
    }

    // ---- AC6: empty view no-op ----

    @Test
    public void shouldNoOp_whenNoEligibleObjects() {
        addNote(); // only an unnamed note on the view

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(TEMPLATE));

        assertEquals(0, result.appliedCount());
    }

    // ---- AC7: single-command undo across set, change, clear ----

    @Test
    public void shouldRestorePriorLabel_whenUndone() {
        IDiagramModelArchimateObject a = addElement("Alpha");
        // pre-existing label
        a.getFeatures().putString(FEATURE, "OLD");

        PreparedMutation<?> prepared = SetViewLabelExpressionCommand.prepare(model, params(TEMPLATE));
        prepared.command().execute();
        assertEquals(TEMPLATE, labelOf(a));

        prepared.command().undo();
        assertEquals("OLD", labelOf(a));
    }

    @Test
    public void shouldClearStaleLabelOnUnnamedElement_whenClearing() {
        // An object with a pre-existing label but no name is skipped on SET (no-ghost guard),
        // but a CLEAR must still remove the stale label — removing a feature has no ghost risk.
        IDiagramModelArchimateObject unnamed = addElement("");
        unnamed.getFeatures().putString(FEATURE, "STALE");

        SetViewLabelExpressionResultDto result = prepareAndExecute(params(""));

        assertNull(labelOf(unnamed));
        assertEquals(1, result.appliedCount());
    }

    @Test
    public void shouldRestoreCleared_whenClearUndone() {
        IDiagramModelArchimateObject a = addElement("Alpha");
        a.getFeatures().putString(FEATURE, "OLD");

        PreparedMutation<?> prepared = SetViewLabelExpressionCommand.prepare(model, params(""));
        prepared.command().execute();
        assertNull(labelOf(a));

        prepared.command().undo();
        assertEquals("OLD", labelOf(a));
    }

    // ---- validation ----

    @Test
    public void shouldThrow_whenViewNotFound() {
        Map<String, Object> params = new java.util.HashMap<>(
                Map.of("viewId", "does-not-exist", "labelExpression", TEMPLATE));
        try {
            SetViewLabelExpressionCommand.prepare(model, params);
            fail("expected ModelAccessException for unknown view");
        } catch (ModelAccessException expected) {
            // ok
        }
    }

    @Test
    public void shouldThrow_whenObjectTypeUnknown() {
        addElement("Alpha");
        Map<String, Object> params = params(TEMPLATE);
        params.put("objectTypes", List.of("connection"));
        try {
            SetViewLabelExpressionCommand.prepare(model, params);
            fail("expected ModelAccessException for unknown object type");
        } catch (ModelAccessException expected) {
            // ok
        }
    }
}
