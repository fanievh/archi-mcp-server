package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.human;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * Pins that {@link UpdateElementCommand}, {@link UpdateRelationshipCommand} and
 * {@link UpdateModelCommand} restore on undo only the fields they wrote, to the values those fields
 * held when the update ran — never a field the update did not name, and never a value from when the
 * command was built.
 *
 * <p>A batched update is built when it is queued and runs when the batch commits; a bulk card is
 * built when the agent proposes and runs when the human approves. An undo that restored every field
 * from the build-time copy reverted a human's rename under a documentation-only update, and an
 * agent's own later change under an approved card.</p>
 */
public class ModelTreeUpdateExecuteTimeSnapshotTest {

    private ApprovalStalenessFixture f;
    private IArchimateElement element;
    private IAssociationRelationship relationship;

    @Before
    public void setUp() {
        f = new ApprovalStalenessFixture();
        ApprovalStalenessFixture.Pair p = f.pair();
        element = (IArchimateElement) p.a().getArchimateElement();
        relationship = (IAssociationRelationship) p.connection().getArchimateRelationship();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static List<String> props(IProperties owner) {
        List<String> out = new ArrayList<>();
        for (IProperty p : owner.getProperties()) {
            out.add(p.getKey() + "=" + p.getValue());
        }
        return out;
    }

    private Command humanAddsProperty(IProperties owner, String key, String value) {
        IProperty added = f.factory.createProperty();
        added.setKey(key);
        added.setValue(value);
        return human("Add property", () -> owner.getProperties().add(added),
                () -> owner.getProperties().remove(added));
    }

    // ---- the batch arm, with the human editing while the batch is open ------------------------

    @Test
    public void undo_shouldLeaveTheHumansNameAndProperty_whenABatchedUpdateWroteOnlyTheDocumentation() {
        f.dispatcher.beginBatch(SESSION, "documentation while a human renames");
        f.accessor.updateElement(SESSION, element.getId(), null, "queued doc", null, null);
        f.stack.execute(human("Rename", () -> element.setName("Human"), () -> element.setName("Ledger")));
        f.stack.execute(humanAddsProperty(element, "owner", "human"));
        assertEquals("precondition: the human's edits landed", "Human", element.getName());
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("queued doc", element.getDocumentation());

        f.stack.undo();
        assertEquals("undo restores the documentation", "", element.getDocumentation());
        assertEquals("and leaves the name it did not write", "Human", element.getName());
        assertEquals("and the properties it did not write", List.of("owner=human"), props(element));
    }

    @Test
    public void undo_shouldLeaveTheHumansRelationshipName_whenABatchedUpdateWroteOnlyTheDocumentation() {
        f.dispatcher.beginBatch(SESSION, "documentation while a human renames");
        f.accessor.updateRelationship(SESSION, relationship.getId(), null, "queued doc", null, null);
        f.stack.execute(human("Rename", () -> relationship.setName("Human"), () -> relationship.setName("")));
        f.dispatcher.endBatch(SESSION, true);

        f.stack.undo();
        assertEquals("", relationship.getDocumentation());
        assertEquals("Human", relationship.getName());
    }

    @Test
    public void undo_shouldLeaveTheHumansModelName_whenABatchedUpdateWroteOnlyThePurpose() {
        f.model.setName("Before");
        f.dispatcher.beginBatch(SESSION, "purpose while a human renames the model");
        f.accessor.updateModel(SESSION, null, "queued purpose", null);
        f.stack.execute(human("Rename model", () -> f.model.setName("Human"), () -> f.model.setName("Before")));
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("queued purpose", f.model.getPurpose());

        f.stack.undo();
        assertEquals("", f.model.getPurpose());
        assertEquals("Human", f.model.getName());
    }

    // ---- the approval arm, with the agent's own change in between -----------------------------

    @Test
    public void undo_shouldLeaveTheAgentsLaterChanges_whenAFrozenBulkRenameIsApprovedOverThem() {
        f.approvalRequired = true;
        String proposal;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", relationship.getId());
            params.put("name", "renamed");
            proposal = f.accessor.executeBulk(SESSION,
                    List.of(new BulkOperation("update-relationship", params, null)), "rename", false)
                    .proposalContext().proposalId();
        } finally {
            f.approvalRequired = false;
        }
        f.accessor.updateRelationship(SESSION, relationship.getId(), null, null, null, null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null));
        f.accessor.updateRelationship(SESSION, relationship.getId(), null, null, Map.of("agent", "later"), null);
        assertTrue("precondition: the agent's own change landed", relationship.isDirected());

        assertNull("an agent-only change is forgiven", f.approveOrRefusal(proposal));
        assertEquals("renamed", relationship.getName());

        f.stack.undo();
        assertEquals("undo restores the name", "", relationship.getName());
        assertTrue("and leaves the direction it did not write", relationship.isDirected());
        assertEquals("and the properties it did not write", List.of("agent=later"), props(relationship));
    }

    // ---- a field the update did not write is never restored -----------------------------------
    //
    // Under the stack's last-in-first-out order a field this update did not write holds, at undo
    // time, what it held when the update ran — unless something changed it without going through
    // the stack. These pins make that change directly on the model object after the update ran.

    @Test
    public void undo_shouldLeaveAFieldItDidNotWrite_whenThatFieldChangedOffTheStackAfterItRan() {
        f.stack.execute(new UpdateElementCommand(element, null, "doc", null));
        element.setName("Changed after");
        f.stack.undo();
        assertEquals("", element.getDocumentation());
        assertEquals("Changed after", element.getName());

        f.stack.execute(new UpdateRelationshipCommand(relationship, "R", null, null));
        relationship.setDirected(true);
        f.stack.undo();
        assertEquals("", relationship.getName());
        assertTrue("an attribute it did not write is left alone", relationship.isDirected());

        String purposeBefore = f.model.getPurpose();
        f.stack.execute(new UpdateModelCommand(f.model, null, "purpose", false, null));
        assertEquals("purpose", f.model.getPurpose());
        f.model.setName("Changed after");
        f.stack.undo();
        assertEquals("Changed after", f.model.getName());
        assertEquals("the purpose it wrote is given back", purposeBefore, f.model.getPurpose());
    }

    // ---- two queued updates on one element ---------------------------------------------------

    @Test
    public void undo_shouldRestoreTheOriginal_whenOneBatchQueuesTwoUpdatesOnOneElement() {
        f.dispatcher.beginBatch(SESSION, "two updates on one element");
        f.accessor.updateElement(SESSION, element.getId(), "Renamed", null, null, null);
        f.accessor.updateElement(SESSION, element.getId(), null, null, Map.of("k", "v"), null);
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("Renamed", element.getName());
        assertEquals(List.of("k=v"), props(element));

        f.stack.undo();
        assertEquals("Ledger", element.getName());
        assertEquals(List.of(), props(element));
    }

    // ---- what the batch readers ask before the update runs ------------------------------------

    @Test
    public void hasNameChange_shouldAnswerFromTheNameWhenBuilt_beforeTheUpdateRuns() {
        relationship.setName("Same");
        UpdateRelationshipCommand unchanged = new UpdateRelationshipCommand(relationship, "Same", null, null);
        UpdateRelationshipCommand renamed = new UpdateRelationshipCommand(relationship, "Other", null, null);
        UpdateRelationshipCommand unnamed = new UpdateRelationshipCommand(relationship, null, "doc", null);
        assertFalse(unchanged.hasNameChange());
        assertTrue(renamed.hasNameChange());
        assertFalse(unnamed.hasNameChange());
    }

    // ---- an undo of a same-value write fires no notification ----------------------------------

    @Test
    public void undo_shouldFireNoNotification_whenTheSemanticAttributeItWroteWasAlreadyHeld() {
        relationship.setDirected(true);
        List<Notification> seen = new ArrayList<>();
        relationship.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                seen.add(n);
            }
        });
        f.stack.execute(new UpdateRelationshipCommand(relationship, null, null, null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null)));
        f.stack.undo();
        assertEquals(List.of(), seen);
    }

    // ---- the snapshot is re-armed by undo -----------------------------------------------------

    @Test
    public void execute_shouldSnapshotAgain_afterAnUndo_forAllThree() {
        UpdateElementCommand el = new UpdateElementCommand(element, "E", null, null);
        UpdateRelationshipCommand rel = new UpdateRelationshipCommand(relationship, "R", null, null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null));
        UpdateModelCommand mo = new UpdateModelCommand(f.model, "M", null, false, null);
        for (Command c : List.of(el, rel, mo)) {
            c.execute();
            c.undo();
        }
        element.setName("between");
        relationship.setName("between");
        f.model.setName("between");
        for (Command c : List.of(el, rel, mo)) {
            c.execute();
            c.undo();
        }
        assertEquals("between", element.getName());
        assertEquals("between", relationship.getName());
        assertFalse(relationship.isDirected());
        assertEquals("between", f.model.getName());
    }

    @Test
    public void execute_shouldKeepItsSnapshot_whenRunAgainWithoutAnUndo() {
        UpdateElementCommand el = new UpdateElementCommand(element, "E", null, Map.of("k", "v"));
        el.execute();
        el.execute();
        el.undo();
        assertEquals("Ledger", element.getName());
        assertEquals(List.of(), props(element));
    }

    @Test
    public void undo_shouldChangeNothing_beforeAnyExecute() {
        f.model.setName("Model");
        new UpdateElementCommand(element, "E", "doc", Map.of("k", "v")).undo();
        new UpdateRelationshipCommand(relationship, "R", "doc", null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null)).undo();
        new UpdateModelCommand(f.model, "M", null, true, null).undo();
        assertEquals("Ledger", element.getName());
        assertEquals("", element.getDocumentation());
        assertEquals("", relationship.getName());
        assertFalse(relationship.isDirected());
        assertEquals("Model", f.model.getName());
    }

    @Test
    public void stack_undoRedoUndo_shouldBeStable() {
        f.stack.execute(new UpdateElementCommand(element, "E", "doc", Map.of("k", "v")));
        for (int round = 0; round < 2; round++) {
            f.stack.undo();
            assertEquals("Ledger", element.getName());
            assertEquals("", element.getDocumentation());
            assertEquals(List.of(), props(element));
            f.stack.redo();
            assertEquals("E", element.getName());
            assertEquals("doc", element.getDocumentation());
            assertEquals(List.of("k=v"), props(element));
        }
    }

    // ---- the immediate arm is unchanged -------------------------------------------------------

    @Test
    public void immediate_shouldStoreAndUndoTheSame_forNameDocumentationPropertiesAndAttributes() {
        IProperty kept = f.factory.createProperty();
        kept.setKey("keep");
        kept.setValue("1");
        element.getProperties().add(kept);
        f.accessor.updateElement(SESSION, element.getId(), "N", "D", Map.of("keep", "2", "add", "3"), null);
        assertEquals("N", element.getName());
        assertEquals("D", element.getDocumentation());
        assertEquals(List.of("keep=2", "add=3"), props(element));
        f.stack.undo();
        assertEquals("Ledger", element.getName());
        assertEquals("", element.getDocumentation());
        assertEquals(List.of("keep=1"), props(element));

        f.accessor.updateRelationship(SESSION, relationship.getId(), "R", "RD", Map.of("p", "q"), null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null));
        assertEquals("R", relationship.getName());
        assertTrue(relationship.isDirected());
        f.stack.undo();
        assertEquals("", relationship.getName());
        assertEquals("", relationship.getDocumentation());
        assertFalse(relationship.isDirected());
        assertEquals(List.of(), props(relationship));

        f.model.setPurpose("P0");
        f.accessor.updateModel(SESSION, "Model", "", Map.of("m", "1"));
        assertEquals("Model", f.model.getName());
        assertEquals("", f.model.getPurpose());
        f.stack.undo();
        assertEquals("P0", f.model.getPurpose());
        assertEquals(List.of(), props(f.model));
    }
}
