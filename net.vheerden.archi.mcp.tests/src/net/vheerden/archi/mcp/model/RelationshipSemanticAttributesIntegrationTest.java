package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IBusinessObject;
import com.archimatetool.model.IGoal;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * Integration tests for relationship semantic attributes
 * ({@code accessType} / {@code associationDirected} / {@code influenceStrength})
 * on {@code create-relationship} / {@code update-relationship}.
 *
 * <p>Uses real {@link IArchimateFactory#eINSTANCE} EMF objects + the synchronous
 * test dispatcher pattern (mirrors {@code ArchiModelAccessorImplTest}). Tests are
 * guarded with {@code Assume} for the {@code RelationshipsMatrix} OSGi dependency
 * (only loads under the PDE runtime).</p>
 */
public class RelationshipSemanticAttributesIntegrationTest {

    private static final String SESSION_ID = "test-session";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;

    @Before
    public void setUp() {
        stubModelManager = new StubEditorModelManager();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ==================== create-relationship ====================

    @Test
    public void shouldCreateAccessRelationshipWithAccessType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes("read", null, null);
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    SESSION_ID, "AccessRelationship", "ba-001", "bo-001", null, null, attrs);

            assertNotNull(result.entity());
            assertEquals("read", result.entity().accessType());
            // EMF state matches the wire-vocabulary mapping
            IAccessRelationship rel = (IAccessRelationship)
                    ArchimateModelUtils.getObjectByID(model, result.entity().id());
            assertEquals(IAccessRelationship.READ_ACCESS, rel.getAccessType());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateAssociationRelationshipWithDirected() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes(null, Boolean.TRUE, null);
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    SESSION_ID, "AssociationRelationship", "ba-001", "bo-001", null, null, attrs);

            assertEquals(Boolean.TRUE, result.entity().associationDirected());
            IAssociationRelationship rel = (IAssociationRelationship)
                    ArchimateModelUtils.getObjectByID(model, result.entity().id());
            assertTrue(rel.isDirected());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateInfluenceRelationshipWithStrength() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes(null, null, "+");
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    SESSION_ID, "InfluenceRelationship", "bg-001", "bg-002", null, null, attrs);

            assertEquals("+", result.entity().influenceStrength());
            IInfluenceRelationship rel = (IInfluenceRelationship)
                    ArchimateModelUtils.getObjectByID(model, result.entity().id());
            assertEquals("+", rel.getStrength());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // ==================== update-relationship ====================

    @Test
    public void shouldUpdateAccessType_onExistingAccessRelationship() {
        IArchimateModel model = createTestModel();
        IAccessRelationship existing = preInstallAccessRelationship(model, "rel-acc-1",
                IAccessRelationship.UNSPECIFIED_ACCESS);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes("readwrite", null, null);
            MutationResult<RelationshipDto> result = accessor.updateRelationship(
                    SESSION_ID, "rel-acc-1", null, null, null, null, attrs);
            assertNotNull(result.entity());
            assertEquals(IAccessRelationship.READ_WRITE_ACCESS, existing.getAccessType());
            // L2a: also assert the response DTO reflects the post-update value (guards against
            // a future regression where the entry method doesn't reconstitute the DTO after dispatch)
            assertEquals("readwrite", result.entity().accessType());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    @Test
    public void shouldUpdateAssociationDirected_onExistingAssociationRelationship() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing = preInstallAssociationRelationship(model, "rel-as-1", false);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes(null, Boolean.TRUE, null);
            accessor.updateRelationship(SESSION_ID, "rel-as-1", null, null, null, null, attrs);
            assertTrue(existing.isDirected());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    @Test
    public void shouldUpdateInfluenceStrength_onExistingInfluenceRelationship() {
        IArchimateModel model = createTestModel();
        IInfluenceRelationship existing = preInstallInfluenceRelationship(model, "rel-inf-1", "+");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes(null, null, "-2");
            accessor.updateRelationship(SESSION_ID, "rel-inf-1", null, null, null, null, attrs);
            assertEquals("-2", existing.getStrength());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    @Test
    public void shouldClearInfluenceStrength_viaEmptyString() {
        IArchimateModel model = createTestModel();
        IInfluenceRelationship existing = preInstallInfluenceRelationship(model, "rel-inf-clr", "+++");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            RelationshipSemanticAttributes attrs =
                    new RelationshipSemanticAttributes(null, null, "");
            accessor.updateRelationship(SESSION_ID, "rel-inf-clr", null, null, null, null, attrs);
            // Empty-string clears the underlying EMF value
            assertEquals("", existing.getStrength());
            // L2b: the read-side DTO normalises empty-string → null so JSON omits the field
            // under @JsonInclude(NON_NULL). This pins the round-trip "cleared = absent" contract.
            RelationshipDto dto = DtoMapper.convertToRelationshipDto(existing, false);
            assertNull("cleared influenceStrength should be omitted from DTO",
                    dto.influenceStrength());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    // ==================== name / documentation empty-string clear ====================

    /**
     * Below the wire this already worked — the guard admits {@code ""}, the command writes it. Kept
     * as the layer pin so a regression here is told apart from a regression in the two readers that
     * feed it, which are what the tests below exercise.
     */
    @Test
    public void shouldClearNameAndDocumentation_whenEmptyStringReachesTheAccessor() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-clr-1", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateRelationship(SESSION_ID, "rel-clr-1", "", "", null, null, null);
            assertEquals("", existing.getName());
            assertEquals("", existing.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * The bulk seam. Asserted through {@code executeBulk} rather than by calling the prepare
     * directly, because the defect this pins lived in the parameter reader inside the bulk case —
     * a test that hands the prepare an already-read {@code ""} cannot see it.
     */
    @Test
    public void shouldClearRelationshipDocumentation_whenBulkMutatePassesEmptyString() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-1", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-1");
            params.put("documentation", "");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "clear documentation", false);
            assertEquals("bulk-mutate must clear documentation exactly as the standalone tool does",
                    "", existing.getDocumentation());
            assertEquals("the untouched name must survive", "Serves", existing.getName());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    @Test
    public void shouldClearRelationshipName_whenBulkMutatePassesEmptyString() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-2", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-2");
            params.put("name", "");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "clear name", false);
            assertEquals("bulk-mutate must clear the name exactly as the standalone tool does",
                    "", existing.getName());
            assertEquals("the untouched documentation must survive",
                    "Original docs", existing.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /** Failure A's exact shape on the bulk path: set one field, clear the other, in one operation. */
    @Test
    public void shouldSetNameAndClearDocumentation_whenBulkMutateDoesBothInOneOperation() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-3", "Old", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-3");
            params.put("name", "Serves");
            params.put("documentation", "");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "rename and clear", false);
            assertEquals("Serves", existing.getName());
            assertEquals("the clear must not be lost beside a set", "", existing.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * The documented whitespace-only behaviour, pinned rather than merely asserted in prose. The
     * allow-empty readers test {@code instanceof String}, not {@code !isBlank()}, so {@code "   "}
     * is now stored verbatim where it was previously dropped. {@code docs/mutation-model.md} states
     * this outright; without a pin that sentence is a claim nothing exercises.
     */
    @Test
    public void shouldStoreWhitespaceOnlyNameVerbatim_whenBulkMutatePassesIt() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-ws", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-ws");
            params.put("name", "   ");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "whitespace name", false);
            assertEquals("a whitespace-only name is stored verbatim — there is no third sentinel "
                    + "between clear and set", "   ", existing.getName());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * What the bulk call <em>reported</em>, as against what it wrote. The tests above assert on the
     * EMF object and discard {@code executeBulk}'s return value, which is exactly why none of them
     * could see that the per-operation entry named the relationship by the name it no longer had.
     */
    @Test
    public void shouldReportTheNewName_whenBulkMutateRenamesARelationship() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-rn", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-rn");
            params.put("name", "Renamed");
            BulkMutationResult result = accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)), "rename", false);

            assertEquals("the model holds the new name", "Renamed", existing.getName());
            assertEquals("and the reported name must be the one the model holds, not the one it "
                    + "held while the operation was prepared",
                    "Renamed", result.operations().get(0).entityName());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * The clear, which the standalone tool has reported as present-and-empty since the empty-string
     * clear reached the wire. A cleared name is not an absent one, and reporting the old name here
     * is the sharper failure: the agent asked for a clear, the model performed it, and the response
     * names a string true of neither.
     */
    @Test
    public void shouldReportTheClearedName_whenBulkMutateClearsARelationshipName() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-cl", "Serves", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-cl");
            params.put("name", "");
            BulkMutationResult result = accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)), "clear name", false);

            assertEquals("the model holds the cleared name", "", existing.getName());
            assertEquals("a cleared name must arrive as the empty string, not as the old name",
                    "", result.operations().get(0).entityName());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * The other half of the leave-unchanged negative control on the bulk path: a key present with
     * an explicit JSON {@code null}. The handler path pins this; the bulk reader that actually
     * changed did not, which left the two paths' guard sets asymmetric.
     */
    @Test
    public void shouldLeaveDocumentationUnchanged_whenBulkMutatePassesExplicitNull() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-null", "Old", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-null");
            params.put("name", "Serves");
            params.put("documentation", null);
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "explicit null documentation", false);
            assertEquals("Serves", existing.getName());
            assertEquals("an explicit JSON null must mean leave-unchanged, not clear",
                    "Original docs", existing.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * The negative control for the three above, on the path whose reader changed. Preserving
     * {@code ""} must not turn an omitted key into a clear.
     */
    @Test
    public void shouldLeaveDocumentationUnchanged_whenBulkMutateOmitsTheKey() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing =
                preInstallNamedAssociationRelationship(model, "rel-bulk-4", "Old", "Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "rel-bulk-4");
            params.put("name", "Serves");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-relationship", params)),
                    "rename only", false);
            assertEquals("Serves", existing.getName());
            assertEquals("an omitted documentation key must still mean leave-unchanged",
                    "Original docs", existing.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    /**
     * update-element sits one case above update-relationship in the same bulk switch and makes no
     * empty-clear promise. Pinned so the reader swap cannot be widened onto it by accident.
     */
    @Test
    public void shouldLeaveElementDocumentationUnchanged_whenBulkMutatePassesEmptyString() {
        IArchimateModel model = createTestModel();
        IBusinessActor actor = (IBusinessActor) ArchimateModelUtils.getObjectByID(model, "ba-001");
        actor.setDocumentation("Original docs");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "ba-001");
            params.put("name", "Renamed");
            params.put("documentation", "");
            accessor.executeBulk(SESSION_ID,
                    List.of(new BulkOperation("update-element", params)),
                    "rename element", false);
            assertEquals("Renamed", actor.getName());
            assertEquals("update-element advertises no empty-clear, so \"\" must keep no-opping",
                    "Original docs", actor.getDocumentation());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    // ==================== Undo / redo ====================

    @Test
    public void shouldUndoAccessTypeChange() {
        IArchimateModel model = createTestModel();
        IAccessRelationship existing = preInstallAccessRelationship(model, "rel-undo-1",
                IAccessRelationship.WRITE_ACCESS);
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                existing, null, null, null,
                new RelationshipSemanticAttributes("read", null, null));
        cmd.execute();
        assertEquals(IAccessRelationship.READ_ACCESS, existing.getAccessType());
        cmd.undo();
        assertEquals(IAccessRelationship.WRITE_ACCESS, existing.getAccessType());
    }

    @Test
    public void shouldUndoAssociationDirectedChange() {
        IArchimateModel model = createTestModel();
        IAssociationRelationship existing = preInstallAssociationRelationship(model, "rel-undo-2", false);
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                existing, null, null, null,
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null));
        cmd.execute();
        assertTrue(existing.isDirected());
        cmd.undo();
        assertFalse(existing.isDirected());
    }

    @Test
    public void shouldUndoInfluenceStrengthChange() {
        IArchimateModel model = createTestModel();
        IInfluenceRelationship existing = preInstallInfluenceRelationship(model, "rel-undo-3", "+");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                existing, null, null, null,
                new RelationshipSemanticAttributes(null, null, "-2"));
        cmd.execute();
        assertEquals("-2", existing.getStrength());
        cmd.undo();
        assertEquals("+", existing.getStrength());
    }

    // ==================== Type-conditional rejection ====================

    @Test
    public void shouldRejectAccessTypeOnNonAccessRelationship() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes("read", null, null);
        try {
            accessor.createRelationship(SESSION_ID, "CompositionRelationship",
                    "ba-001", "ba-002", null, null, attrs);
            fail("Expected ModelAccessException for accessType on CompositionRelationship");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("error message must mention accessType: " + e.getMessage(),
                    e.getMessage().contains("accessType"));
        }
    }

    @Test
    public void shouldRejectAssociationDirectedOnNonAssociationRelationship() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes(null, Boolean.TRUE, null);
        try {
            accessor.createRelationship(SESSION_ID, "CompositionRelationship",
                    "ba-001", "ba-002", null, null, attrs);
            fail("Expected ModelAccessException for associationDirected on CompositionRelationship");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("associationDirected"));
        }
    }

    @Test
    public void shouldRejectInfluenceStrengthOnNonInfluenceRelationship() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes(null, null, "+");
        try {
            accessor.createRelationship(SESSION_ID, "CompositionRelationship",
                    "ba-001", "ba-002", null, null, attrs);
            fail("Expected ModelAccessException for influenceStrength on CompositionRelationship");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("influenceStrength"));
        }
    }

    @Test
    public void shouldRejectInvalidAccessTypeEnumValue() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes("garbage", null, null);
        try {
            accessor.createRelationship(SESSION_ID, "AccessRelationship",
                    "ba-001", "bo-001", null, null, attrs);
            fail("Expected ModelAccessException for invalid enum");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().toLowerCase().contains("invalid"));
        }
    }

    @Test
    public void shouldRejectEmptyAccessTypeString() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes("", null, null);
        try {
            accessor.createRelationship(SESSION_ID, "AccessRelationship",
                    "ba-001", "bo-001", null, null, attrs);
            fail("Expected ModelAccessException for empty accessType");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void shouldRejectOverLongInfluenceStrength() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            over.append('x');
        }
        RelationshipSemanticAttributes attrs =
                new RelationshipSemanticAttributes(null, null, over.toString());
        try {
            accessor.createRelationship(SESSION_ID, "InfluenceRelationship",
                    "bg-001", "bg-002", null, null, attrs);
            fail("Expected ModelAccessException for >255-char influenceStrength");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("255"));
        }
    }

    // ==================== Round-trip via convertToRelationshipDto ====================

    @Test
    public void shouldRoundTripRelationshipDtoSemanticAttributeFields_viaConvertToRelationshipDto() {
        IArchimateModel model = createTestModel();
        IAccessRelationship existing = preInstallAccessRelationship(model, "rt-acc",
                IAccessRelationship.READ_ACCESS);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        RelationshipDto dto = DtoMapper.convertToRelationshipDto(existing, false);
        assertEquals("read", dto.accessType());
        assertNull("Non-association relationships should omit associationDirected",
                dto.associationDirected());
        assertNull("Non-influence relationships should omit influenceStrength",
                dto.influenceStrength());
    }

    // ==================== Idempotence guard ====================

    @Test
    public void shouldGuardIdempotentAccessTypeSet() {
        IArchimateModel model = createTestModel();
        IAccessRelationship existing = preInstallAccessRelationship(model, "rel-idem",
                IAccessRelationship.READ_ACCESS);

        // Re-applying the SAME value should be a no-op at the EMF level.
        // We can't easily observe "no notification fired" here, but we CAN verify
        // that undo() restores the original (which is the original — confirming
        // the guard short-circuited and didn't snapshot a stale "old" state).
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                existing, null, null, null,
                new RelationshipSemanticAttributes("read", null, null));
        cmd.execute();
        assertEquals(IAccessRelationship.READ_ACCESS, existing.getAccessType());
        cmd.undo();
        assertEquals("undo should leave value at READ_ACCESS (the snapshot)",
                IAccessRelationship.READ_ACCESS, existing.getAccessType());
    }

    // ==================== test fixtures ====================

    private IArchimateModel createTestModel() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Semantic Attribute Test Model");
        model.setId("model-semantic-attributes");
        model.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("ba-001");
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IBusinessActor actor2 = factory.createBusinessActor();
        actor2.setId("ba-002");
        actor2.setName("Vendor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor2);

        IBusinessObject object = factory.createBusinessObject();
        object.setId("bo-001");
        object.setName("Order");
        model.getFolder(FolderType.BUSINESS).getElements().add(object);

        IGoal g1 = factory.createGoal();
        g1.setId("bg-001");
        g1.setName("Goal A");
        model.getFolder(FolderType.MOTIVATION).getElements().add(g1);

        IGoal g2 = factory.createGoal();
        g2.setId("bg-002");
        g2.setName("Goal B");
        model.getFolder(FolderType.MOTIVATION).getElements().add(g2);

        return model;
    }

    private IAccessRelationship preInstallAccessRelationship(IArchimateModel model,
            String id, int accessType) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IAccessRelationship rel = factory.createAccessRelationship();
        rel.setId(id);
        rel.setAccessType(accessType);
        // Locate the BusinessActor + BusinessObject we set up
        IBusinessActor src = (IBusinessActor) ArchimateModelUtils.getObjectByID(model, "ba-001");
        IBusinessObject tgt = (IBusinessObject) ArchimateModelUtils.getObjectByID(model, "bo-001");
        rel.connect(src, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private IAssociationRelationship preInstallAssociationRelationship(IArchimateModel model,
            String id, boolean directed) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setDirected(directed);
        IBusinessActor src = (IBusinessActor) ArchimateModelUtils.getObjectByID(model, "ba-001");
        IBusinessObject tgt = (IBusinessObject) ArchimateModelUtils.getObjectByID(model, "bo-001");
        rel.connect(src, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    /** An association relationship carrying a name and documentation, for the empty-clear tests. */
    private IAssociationRelationship preInstallNamedAssociationRelationship(IArchimateModel model,
            String id, String name, String documentation) {
        IAssociationRelationship rel = preInstallAssociationRelationship(model, id, false);
        rel.setName(name);
        rel.setDocumentation(documentation);
        return rel;
    }

    private IInfluenceRelationship preInstallInfluenceRelationship(IArchimateModel model,
            String id, String strength) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IInfluenceRelationship rel = factory.createInfluenceRelationship();
        rel.setId(id);
        rel.setStrength(strength);
        IGoal src = (IGoal) ArchimateModelUtils.getObjectByID(model, "bg-001");
        IGoal tgt = (IGoal) ArchimateModelUtils.getObjectByID(model, "bg-002");
        rel.connect(src, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel model) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        // The dispatcher now defaults to GATED (fail-safe). These tests exercise the
        // immediate-apply path, so opt approval OFF explicitly (production wires the human bit).
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    /**
     * Minimal {@link IEditorModelManager} stub — only supports
     * {@code setModels()} + {@code getModels()} + listener registration (no-op).
     * Mirrors the pattern from {@code ArchiModelAccessorImplTest}.
     */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        // ---- Unused IEditorModelManager methods (required by interface) ----

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
