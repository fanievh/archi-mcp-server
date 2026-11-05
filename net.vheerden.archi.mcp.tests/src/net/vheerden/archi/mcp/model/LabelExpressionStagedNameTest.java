package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

/**
 * Pins that {@code set-view-label-expression} decides whether an element has a name to show by the name
 * the staged work leaves it, not the one it had before: an element an earlier operation in the call or
 * the batch renames from nothing to a name is labelled, and the counts it answers are what the commit
 * stamps. A rename to nothing cannot be staged ({@code update-element} refuses an empty name), so only
 * that direction is reachable.
 */
public class LabelExpressionStagedNameTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;
    private IArchimateElement nameless;
    private IDiagramModelArchimateObject object;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-label-staged-name");
        nameless = f.element("nameless");
        nameless.setName("");
        object = f.object(f.view, "vo-1", nameless, 40, 40, 120, 55);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void shouldLabel_anElementAnEarlierOperationInTheCallNamed() {
        BulkMutationResult result = bulk(rename("Named"), label());

        BulkOperationResult labelled = result.operations().get(1);
        assertEquals(Integer.valueOf(1), labelled.appliedCount());
        assertEquals(Integer.valueOf(0), labelled.skippedCount());
        assertTrue("the commit stamps what the count says", object.getFeatures().has("labelExpression"));
    }

    @Test
    public void shouldLabel_anElementTheBatchQueuedANameFor() {
        f.dispatcher.beginBatch(SESSION, "queued rename");
        f.accessor.updateElement(SESSION, nameless.getId(), "Named", null, null, null);
        BulkOperationResult labelled = bulk(label()).operations().get(0);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(Integer.valueOf(1), labelled.appliedCount());
        assertEquals(Integer.valueOf(0), labelled.skippedCount());
        assertTrue(object.getFeatures().has("labelExpression"));
    }

    /** The last staged rename is the one that counts; a rename with no name in it leaves the name alone. */
    @Test
    public void shouldReadTheLastStagedRename() {
        BulkMutationResult result = bulk(rename("Named"), op("update-element",
                Map.of("id", nameless.getId(), "documentation", "doc only")), label());

        assertEquals(Integer.valueOf(1), result.operations().get(2).appliedCount());
    }

    /** Of two staged renames, the reader answers the later one. */
    @Test
    public void nameOf_shouldAnswerTheLaterOfTwoStagedRenames() {
        f.dispatcher.beginBatch(SESSION, "two renames");
        f.accessor.updateElement(SESSION, nameless.getId(), "First", null, null, null);
        f.accessor.updateElement(SESSION, nameless.getId(), "Second", null, null, null);
        String name = StagedViewState.of(f.dispatcher, SESSION, null).nameOf(object);
        f.dispatcher.endBatch(SESSION, false);

        assertEquals("Second", name);
    }

    /** A group's label is its name, which {@code update-view-object} sets through its text. */
    @Test
    public void shouldLabel_aGroupAnEarlierOperationInTheCallNamed() {
        IDiagramModelGroup group = f.group(f.view, "g-1", 300, 40, 200, 150);
        group.setName("");
        BulkMutationResult result = bulk(op("update-view-object", Map.of("viewObjectId", "g-1", "text", "Named group")),
                op("set-view-label-expression", Map.of("viewId", f.view.getId(), "labelExpression", "${name}!",
                        "objectTypes", List.of("group"))));

        BulkOperationResult labelled = result.operations().get(1);
        assertEquals(Integer.valueOf(1), labelled.appliedCount());
        assertEquals("only the element, which is not a group", Integer.valueOf(1), labelled.skippedCount());
        assertTrue(group.getFeatures().has("labelExpression"));
    }

    /** The control: with no staged rename, a nameless element is skipped exactly as before. */
    @Test
    public void shouldSkipANamelessElement_withNoStagedRename() {
        BulkOperationResult labelled = bulk(label()).operations().get(0);

        assertEquals(Integer.valueOf(0), labelled.appliedCount());
        assertEquals(Integer.valueOf(1), labelled.skippedCount());
        assertFalse(object.getFeatures().has("labelExpression"));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    BulkOperation rename(String name) {
        return op("update-element", Map.of("id", nameless.getId(), "name", name));
    }

    BulkOperation label() {
        return op("set-view-label-expression", Map.of("viewId", f.view.getId(), "labelExpression", "${name}!"));
    }

    static BulkOperation op(String tool, Map<String, Object> params) {
        return new BulkOperation(tool, params, null);
    }

    BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "staged names", false, null);
    }
}
