package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
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
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Naming an earlier operation by a label rather than by its position, asserted end to end.
 *
 * <p>Every assertion runs through the registered tool and reads the serialized JSON a caller
 * actually receives, because the bulk path hand-builds its per-operation map key by key and a value
 * computed but never copied across would satisfy a typed assertion while nothing reached the
 * wire.</p>
 *
 * <p><strong>Every fixture here is deliberately non-degenerate.</strong> The declaration sits at
 * index 2 or later with an operation that creates nothing between it and the reference, and a
 * second, nearer create sits between them as a wrong answer the assertion can distinguish. At index
 * 0 the request position, the position among creates only, and "the create immediately before this
 * one" all coincide, so a fixture built there is green under a mutation that breaks every payload a
 * caller would actually write.</p>
 *
 * <p>The two modes where nothing is written — queued into an open batch, and parked awaiting
 * approval — are pinned here rather than left to reasoning, because they are the modes where the
 * shipped {@code parentViewObjectId} disclosure reports nothing at all. Resolution happens in the
 * prepare pass, upstream of the mode branch, so a name is checked in all four arms while the
 * container is reported in one; that difference is the reason this form was built and it is
 * asserted rather than assumed.</p>
 *
 * <p>Fixture labels are real domain words. Letter-and-digit shapes are internal project-code
 * families that a contract test scans the shipped surface for, and a fixture that invents one
 * pushes that family's count without meaning to.</p>
 */
public class BulkNamedBackReferenceTest {

    private static final String SESSION = "default";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Named Back Reference Fixture");
        model.setId("model-named-back-reference");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Placement");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 5; i++) {
            IBusinessActor actor = factory.createBusinessActor();
            actor.setId("actor-" + i);
            actor.setName("Actor " + i);
            business.getElements().add(actor);
        }

        // A relationship that already exists, because create-relationship cannot run in this lane:
        // Archi's RelationshipsMatrix needs a real OSGi bundle to class-init. Asserting the routing
        // branch does not need the relationship to be new — the branch is entered on the view
        // objects the same call created, which is what the pin is about.
        com.archimatetool.model.IAssociationRelationship association =
                factory.createAssociationRelationship();
        association.setId("rel-1");
        association.setName("serves");
        association.setSource((IBusinessActor) business.getElements().get(0));
        association.setTarget((IBusinessActor) business.getElements().get(1));
        model.getFolder(FolderType.RELATIONS).getElements().add(association);

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

    // ---- the payload every pin below is a variation of --------------------------------------

    /**
     * Six operations: two that create nothing, the labelled declaration at index 2, another
     * operation that creates nothing, a second labelled create at index 4, and the reference at
     * index 5. Index 4 is the answer a mistyped position gives and the answer "the create before
     * this one" gives, so an assertion that the reference landed in index 2's group rules both out.
     */
    private List<Map<String, Object>> nonDegenerateOperations(String reference) {
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(Map.of("tool", "update-view", "params", Map.of(
                "viewId", view.getId(), "name", "Placement")));
        ops.add(Map.of("tool", "update-element", "params", Map.of(
                "id", "actor-5", "documentation", "touched, creates nothing")));
        ops.add(Map.of("tool", "add-group-to-view", "as", "coreBanking", "params", Map.of(
                "viewId", view.getId(), "label", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400)));
        ops.add(Map.of("tool", "update-view", "params", Map.of(
                "viewId", view.getId(), "name", "Placement")));
        ops.add(Map.of("tool", "add-group-to-view", "as", "paymentsZone", "params", Map.of(
                "viewId", view.getId(), "label", "Payments Zone",
                "x", 560, "y", 20, "width", 300, "height", 200)));
        ops.add(Map.of("tool", "add-to-view", "params", Map.of(
                "viewId", view.getId(), "elementId", "actor-1",
                "parentViewObjectId", reference,
                "x", 30, "y", 30, "width", 120, "height", 55)));
        return ops;
    }

    // ---- a name resolves to the operation that declared it -------------------------------------

    /**
     * The write response and a read-back must agree, and both must name index 2's group.
     *
     * <p>Three surfaces are asserted, not one: the per-operation {@code parentViewObjectId} on the
     * serialized write response, the same field on a {@code get-view-contents} read of the finished
     * view, and live EMF containment. A response that agreed with itself and not with the model
     * would be the exact failure the effective-state rule exists to stop.</p>
     */
    @Test
    public void shouldPlaceTheSubjectInsideTheOperationTheNameDeclared() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = nonDegenerateOperations("$coreBanking.id");
        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "nest by name"));
        List<Map<String, Object>> results = operationsOf(response);

        String declaredGroup = (String) results.get(2).get("entityId");
        String nearerGroup = (String) results.get(4).get("entityId");
        String placed = (String) results.get(5).get("entityId");
        assertNotNull("the declaration must have created something", declaredGroup);
        assertNotEquals(declaredGroup, nearerGroup);

        assertEquals("the write response must name the operation that declared the label",
                declaredGroup, results.get(5).get("parentViewObjectId"));
        assertNotEquals("and must not name the nearer create, which is what a mistyped position "
                + "and an append-position framing would both give", nearerGroup,
                results.get(5).get("parentViewObjectId"));

        assertEquals("a read of the finished view must agree with the write response",
                declaredGroup, readBackParentOf(registry, placed));

        EObject live = ArchimateModelUtils.getObjectByID(model, placed);
        assertTrue(live instanceof IDiagramModelObject);
        assertEquals("and the model itself must hold what both of them reported",
                declaredGroup, ((IDiagramModelObject) live).eContainer() instanceof IDiagramModelObject box
                        ? box.getId() : null);
    }

    /** A name and a position in the same payload: which branch runs depends on the token's shape. */
    @Test
    public void shouldResolveANameAndAPositionInTheSameCall() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.add(Map.of("tool", "add-to-view", "params", Map.of(
                "viewId", view.getId(), "elementId", "actor-2",
                "parentViewObjectId", "$4.id",
                "x", 30, "y", 30, "width", 120, "height", 55)));

        List<Map<String, Object>> results = operationsOf(invokeTool(registry, "bulk-mutate",
                Map.of("operations", ops, "description", "both forms in one call")));

        assertEquals("the named reference must reach index 2",
                results.get(2).get("entityId"), results.get(5).get("parentViewObjectId"));
        assertEquals("and the positional one must still reach index 4",
                results.get(4).get("entityId"), results.get(6).get("parentViewObjectId"));
    }

    // ---- a name that no operation declared ------------------------------------------------------

    /**
     * The refusal, and the thing the refusal is there to prevent.
     *
     * <p>{@code parentViewObjectId} is optional, and substitution has no null check, so a name that
     * resolved to null would be dropped in silence and the element would land on the view root with
     * a success response. Asserting only the error text would stay green if a later change put the
     * drop back beside a cosmetic message, so the view is read afterwards as well.</p>
     */
    @Test
    public void shouldRefuseAMisspeltName_andPlaceNothingAtTheViewRoot() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBankng.id"),
                "description", "a name with a letter missing"));

        Map<String, Object> error = errorOf(response);
        assertEquals("BULK_VALIDATION_FAILED", error.get("code"));
        assertTrue("the refusal must name the label the caller wrote: " + error,
                String.valueOf(error).contains("coreBankng"));
        assertEquals("and blame the operation that wrote it", 5, refusedOperationIndex(response));

        assertTrue("nothing may be placed by a refused call, and least of all at the view root: "
                + topLevelActorIds(registry), topLevelActorIds(registry).isEmpty());
    }

    /** The same under continueOnError, where the valid operations do run. */
    @Test
    public void shouldRefuseOnlyTheReferencingOperation_whenTheNameIsMisspeltUnderContinueOnError()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> result = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBankng.id"),
                "continueOnError", true,
                "description", "a name with a letter missing")));

        List<Map<String, Object>> failed = failedOf(result);
        assertEquals("exactly the referencing operation must fail: " + failed, 1, failed.size());
        assertEquals(5, ((Number) failed.get(0).get("index")).intValue());
        assertTrue(failed.get(0).toString(),
                failed.get(0).toString().contains("coreBankng"));

        assertTrue("the element must not have landed on the view root instead: "
                + topLevelActorIds(registry), topLevelActorIds(registry).isEmpty());
    }

    // ---- a name two operations both claim -------------------------------------------------------

    @Test
    public void shouldRefuseBothDeclarations_whenTwoOperationsClaimTheSameName() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.set(4, Map.of("tool", "add-group-to-view", "as", "coreBanking", "params", Map.of(
                "viewId", view.getId(), "label", "Payments Zone",
                "x", 560, "y", 20, "width", 300, "height", 200)));

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "one name, two operations"));

        String rendered = String.valueOf(response);
        assertTrue("the refusal must name both indices: " + rendered,
                rendered.contains("index 2") && rendered.contains("index 4"));
        assertTrue("and the name: " + rendered, rendered.contains("coreBanking"));
        assertEquals("INVALID_PARAMETER", failedRowsOf(response).get(0).get("errorCode"));
    }

    // ---- a name written under the wrong key ----------------------------------------------------

    /**
     * The neighbour of the silence this form closes. Before this, a top-level key the parse did not
     * recognise was discarded without a word, so an operation carrying {@code "As"} looked
     * unlabelled and the failure surfaced on whichever later operation referenced the name —
     * displaced blame, which is the same shape as a mis-aimed position.
     */
    @Test
    public void shouldRefuseAnOperationKeyItDoesNotRecognise_ratherThanDiscardIt() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        for (String strayKey : List.of("As", "label", "name", "alias")) {
            List<Map<String, Object>> ops = new ArrayList<>(
                    nonDegenerateOperations("$coreBanking.id"));
            Map<String, Object> stray = new LinkedHashMap<>();
            stray.put("tool", "add-group-to-view");
            stray.put(strayKey, "coreBanking");
            stray.put("params", Map.of("viewId", view.getId(), "label", "Core Banking",
                    "x", 20, "y", 20, "width", 500, "height", 400));
            ops.set(2, stray);

            String rendered = String.valueOf(invokeTool(registry, "bulk-mutate", Map.of(
                    "operations", ops, "description", "a name under the wrong key")));
            assertTrue("'" + strayKey + "' must be named in the refusal: " + rendered,
                    rendered.contains(strayKey));
            assertTrue("and the accepted set stated: " + rendered,
                    rendered.contains("tool, params, as"));
        }
    }

    /** A name that is not a string is refused the same way, rather than silently coerced. */
    @Test
    public void shouldRefuseANameThatIsNotAString() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.set(2, Map.of("tool", "add-group-to-view", "as", 7, "params", Map.of(
                "viewId", view.getId(), "label", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400)));

        String rendered = String.valueOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "a name that is a number")));
        assertTrue(rendered, rendered.contains("'as' must be a string"));
    }

    /** A name the grammar does not accept is refused where it is declared. */
    @Test
    public void shouldRefuseAnIllegalNameWhereItIsDeclared_notWhereItIsUsed() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.set(2, Map.of("tool", "add-group-to-view", "as", "core-banking", "params", Map.of(
                "viewId", view.getId(), "label", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400)));

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "a hyphen in a name"));
        String rendered = String.valueOf(response);

        assertEquals("INVALID_PARAMETER", failedRowsOf(response).get(0).get("errorCode"));
        assertTrue("the refusal must quote the illegal name: " + rendered,
                rendered.contains("core-banking"));
        assertEquals("and be reported against the operation that declared it, not the one that "
                + "used it", 2, ((Number) failedRowsOf(response).get(0).get("index")).intValue());
    }

    /**
     * An operation refused over its own name must not run anyway.
     *
     * <p>The refusal is recorded before the prepare loop starts, so without a skip the loop would
     * happily prepare and execute the very operation the caller was told had failed — visible only
     * under {@code continueOnError}, where the rest of the call does run.</p>
     */
    @Test
    public void shouldNotExecuteAnOperationItRefusedOverItsName() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$paymentsZone.id"));
        ops.set(2, Map.of("tool", "add-group-to-view", "as", "core-banking", "params", Map.of(
                "viewId", view.getId(), "label", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400)));

        Map<String, Object> result = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "continueOnError", true,
                "description", "an illegal name under continueOnError")));

        assertTrue("the operation with the illegal name must be reported: " + failedOf(result),
                failedOf(result).stream()
                        .anyMatch(row -> ((Number) row.get("index")).intValue() == 2));
        assertEquals("and must not have created its group anyway — one labelled group was legal, "
                + "so exactly one may exist", 1, groupCount());
    }

    /** Two operations under the wrong key are both reported, not one per round-trip. */
    @Test
    public void shouldReportEveryUnrecognisedKeyTogether() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        Map<String, Object> first = new LinkedHashMap<>(ops.get(2));
        first.remove("as");
        first.put("As", "coreBanking");
        ops.set(2, first);
        Map<String, Object> second = new LinkedHashMap<>(ops.get(4));
        second.remove("as");
        second.put("label", "paymentsZone");
        ops.set(4, second);

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "two names under wrong keys"));

        List<Map<String, Object>> rows = failedRowsOf(response);
        assertEquals("both operations must be named in one refusal: " + rows, 2, rows.size());
        assertEquals(2, ((Number) rows.get(0).get("index")).intValue());
        assertEquals(4, ((Number) rows.get(1).get("index")).intValue());
        // Asserted on the SECOND row specifically. The call-level correction describes the first
        // offender only, so a caller reading any later row would otherwise be told which key is
        // wrong and never which keys are right.
        assertTrue("every row must name the accepted set, not just the first: " + rows.get(1),
                String.valueOf(rows.get(1).get("message")).contains("tool, params, as"));
    }

    /**
     * One operation, two distinct shape problems, both reported.
     *
     * <p>A stray key and a non-string name are independent defects that can sit on the same
     * operation. Reporting whichever was checked first sends the caller back for a second
     * round-trip to discover the other, which is precisely the cost this call's report-everything
     * contract exists to remove — so it must not be reintroduced one field down.</p>
     */
    @Test
    public void shouldReportBothProblems_whenAnOperationHasAStrayKeyAndANonStringName()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("tool", "add-group-to-view");
        broken.put("as", 7);
        broken.put("labelName", "coreBanking");
        broken.put("params", Map.of("viewId", view.getId(), "label", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400));
        ops.set(2, broken);

        String rendered = String.valueOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "a stray key and a numeric name")));

        assertTrue("the stray key must be named: " + rendered, rendered.contains("labelName"));
        assertTrue("and the name's type must be named in the SAME row: " + rendered,
                rendered.contains("'as' must be a string"));
    }

    /**
     * An unrecognised key refuses the whole call even under {@code continueOnError}, and that is
     * load-bearing rather than incidental.
     *
     * <p>An operation whose shape cannot be read is never added to the list the accessor receives,
     * and the accessor keys its back-reference maps by position <em>in that list</em>. So tolerating
     * a malformed operation under {@code continueOnError} would silently shift every later
     * positional reference by one — a legal, wrong resolution of exactly the kind this whole
     * feature exists to make impossible. The abort is what stops that, so it is pinned here
     * together with the alignment it protects: fix the key, resend, and {@code $2.id} must still
     * mean operation 2.</p>
     */
    @Test
    public void shouldRefuseTheWholeCallOnAnUnrecognisedKey_evenUnderContinueOnError()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> op : nonDegenerateOperations("$2.id")) {
            Map<String, Object> stripped = new LinkedHashMap<>(op);
            stripped.remove("as");
            ops.add(stripped);
        }
        Map<String, Object> stray = new LinkedHashMap<>(ops.get(1));
        stray.put("comment", "a client's own bookkeeping field");
        ops.set(1, stray);

        Map<String, Object> refused = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "continueOnError", true,
                "description", "a stray key under continueOnError"));

        // INVALID_PARAMETER, not BULK_VALIDATION_FAILED: an operation whose shape cannot be read
        // is refused by the parse loop's own collector, which never reaches the accessor and so
        // never produces a validation verdict. The two collectors are distinguishable on the wire,
        // and this pin is the one place that says so.
        assertEquals("INVALID_PARAMETER", errorOf(refused).get("code"));
        assertTrue("nothing may run: a tolerated malformed operation would renumber every later "
                + "positional reference. Groups present: " + groupCount(), groupCount() == 0);

        // And with the key removed the same payload runs, with $2.id still meaning operation 2 —
        // the alignment the abort protects, asserted rather than argued.
        ops.set(1, new LinkedHashMap<>(nonDegenerateOperations("$2.id").get(1)));
        List<Map<String, Object>> results = operationsOf(invokeTool(registry, "bulk-mutate",
                Map.of("operations", ops, "continueOnError", true,
                        "description", "the same payload, key removed")));
        assertEquals("$2.id must still name the operation at index 2",
                results.get(2).get("entityId"), results.get(5).get("parentViewObjectId"));
    }

    // ---- the cascade sees a name exactly as resolution does -------------------------------------

    /**
     * Under {@code continueOnError}, an operation naming a label whose declaring operation failed
     * must be told so by name.
     *
     * <p>This is the pin the class javadoc's symmetry claim rests on. Give {@code checkCascade} a
     * digits-only pattern of its own and the name becomes invisible to it: the reference then
     * resolves to the id the failed operation never created, which is null, and on this optional
     * parameter it is dropped in silence and the element lands on the view root with a success
     * response. So the placement is asserted too, not just the message.</p>
     */
    @Test
    public void shouldCascadeByName_whenTheOperationThatDeclaredTheNameFailed() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        // The declaration now names a view that does not exist, so index 2 fails on its own terms
        // and index 5's reference has nothing left to point at.
        ops.set(2, Map.of("tool", "add-group-to-view", "as", "coreBanking", "params", Map.of(
                "viewId", "view-does-not-exist", "name", "Core Banking",
                "x", 20, "y", 20, "width", 500, "height", 400)));

        Map<String, Object> result = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "continueOnError", true,
                "description", "the declaration fails")));

        Map<String, Object> cascaded = failedOf(result).stream()
                .filter(row -> ((Number) row.get("index")).intValue() == 5)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the referencing operation must be reported: " + failedOf(result)));

        assertEquals("BACK_REFERENCE_FAILED", cascaded.get("errorCode"));
        assertTrue("the cascade must name the label, not a position the caller never wrote: "
                + cascaded, String.valueOf(cascaded.get("message")).contains("coreBanking"));
        assertTrue("and nothing may have landed at the view root: " + topLevelActorIds(registry),
                topLevelActorIds(registry).isEmpty());
    }

    /**
     * The named form reaches the same downstream branch the positional one does.
     *
     * <p>{@code add-connection-to-view} is routed inside the bulk pass to a second, back-reference
     * aware prepare as soon as any of its four ids names something this call created. That routing
     * keys on the <em>resolved</em> id found in the created-object maps rather than on the token,
     * so it cannot see which form was written — but "cannot see" is a claim about code that is
     * cheap to assert and expensive to be wrong about, since which prepare runs decides whether the
     * endpoints resolve at all.</p>
     */
    @Test
    public void shouldRouteAConnectionThroughTheSameBranch_whenItsIdsAreNamedRatherThanPositional()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = List.of(
                Map.of("tool", "update-view", "params", Map.of(
                        "viewId", view.getId(), "name", "Placement")),
                Map.of("tool", "update-element", "params", Map.of(
                        "id", "actor-5", "documentation", "creates nothing")),
                Map.of("tool", "update-view", "params", Map.of(
                        "viewId", view.getId(), "name", "Placement")),
                Map.of("tool", "add-to-view", "as", "customerBox", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "x", 20, "y", 20, "width", 120, "height", 55)),
                Map.of("tool", "add-to-view", "as", "portalBox", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-2",
                        "x", 300, "y", 20, "width", 120, "height", 55)),
                Map.of("tool", "add-connection-to-view", "params", Map.of(
                        "viewId", view.getId(),
                        "relationshipId", "rel-1",
                        "sourceViewObjectId", "$customerBox.id",
                        "targetViewObjectId", "$portalBox.id")));

        List<Map<String, Object>> results = operationsOf(invokeTool(registry, "bulk-mutate",
                Map.of("operations", ops, "description", "a connection named three ways")));

        assertEquals("all six operations must have run: " + results, 6, results.size());
        assertNotEquals("the two placements must be distinct, or the endpoints do not discriminate",
                results.get(3).get("entityId"), results.get(4).get("entityId"));
        EObject connection = ArchimateModelUtils.getObjectByID(model,
                (String) results.get(5).get("entityId"));
        assertTrue("the connection must exist on the view, which it can only do if the endpoints "
                + "resolved through the branch that reads this call's own creations: " + results,
                connection instanceof com.archimatetool.model.IDiagramModelArchimateConnection);
    }

    // ---- the other dollar namespace -------------------------------------------------------------

    /**
     * Archi's label-expression grammar puts a brace after the dollar, so it cannot collide with
     * either reference form. Asserted in a call that also carries a label, because that is the only
     * arrangement in which the two namespaces are ever both live.
     */
    @Test
    public void shouldLeaveALabelExpressionByteForByte_whenTheSameCallAlsoNamesAnOperation()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String expression = "${name} ${property:evidenceMark}";
        // Placed before the call, because set-view-label-expression resolves its targets when it is
        // prepared and an object this same call is about to create is not among them yet. That is
        // pre-existing behaviour of the fan-out and not what this pin is about.
        String preExisting = (String) viewObjectOf(invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-3",
                "x", 900, "y", 20, "width", 120, "height", 55))).get("viewObjectId");

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.add(Map.of("tool", "set-view-label-expression", "params", Map.of(
                "viewId", view.getId(), "labelExpression", expression)));

        List<Map<String, Object>> results = operationsOf(invokeTool(registry, "bulk-mutate",
                Map.of("operations", ops,
                        "description", "a label expression beside a named operation")));

        assertEquals("the named reference must still land where its name said",
                results.get(2).get("entityId"), results.get(5).get("parentViewObjectId"));

        EObject stamped = ArchimateModelUtils.getObjectByID(model, preExisting);
        assertTrue(stamped instanceof IDiagramModelObject);
        assertEquals("the expression must reach the model exactly as it was written — a walk that "
                + "substituted into it would have eaten the ${name} first",
                expression, ((IDiagramModelObject) stamped).getFeatures()
                        .getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
    }

    // ---- the arms where nothing is written ------------------------------------------------------

    /**
     * Queued into an open batch, a misspelt name is still refused.
     *
     * <p>This is the measured reason the form was built. The shipped {@code parentViewObjectId}
     * disclosure reports nothing at all in this mode — the post-dispatch read is skipped wholesale
     * because nothing has been written — so a mis-aimed positional reference here is disclosed by
     * no field on the response. A name is checked in the prepare pass, which runs upstream of the
     * mode branch, so it refuses here exactly as it does on an applied call.</p>
     */
    @Test
    public void shouldRefuseAMisspeltName_whileQueuedIntoAnOpenBatch() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of("description", "queue a named placement"));

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBankng.id"),
                "description", "a misspelt name inside a batch"));

        assertEquals("BULK_VALIDATION_FAILED", errorOf(response).get("code"));
        assertEquals(5, refusedOperationIndex(response));
        assertTrue("the refusal must name the label: " + response,
                String.valueOf(response).contains("coreBankng"));
    }

    /** And a well-formed one still resolves there, so the refusal above is not a blanket one. */
    @Test
    public void shouldResolveANameWhileQueuedIntoAnOpenBatch() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of("description", "queue a named placement"));

        Map<String, Object> queued = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBanking.id"),
                "description", "a good name inside a batch")));
        assertTrue("the queued response must declare itself a preview rather than state",
                queued.containsKey("batch") || queued.containsKey("preview"));

        invokeTool(registry, "end-batch", Map.of());

        List<Map<String, Object>> ops = operationsOfResult(queued);
        String declaredGroup = (String) ops.get(2).get("entityId");
        EObject placed = ArchimateModelUtils.getObjectByID(model, (String) ops.get(5).get("entityId"));
        assertTrue("the committed batch must have placed it", placed instanceof IDiagramModelObject);
        assertEquals("inside the group the name declared", declaredGroup,
                ((IDiagramModelObject) placed).eContainer() instanceof IDiagramModelObject box
                        ? box.getId() : null);
    }

    /**
     * Parked awaiting approval, a misspelt name is refused too.
     *
     * <p>The approval arm had never been measured for a back-reference of either form. It is
     * measured here for a call that is in approval mode throughout — which is what a fixture can
     * observe. A human toggling the mode mid-call is a different question this cannot see, and is
     * not claimed.</p>
     */
    @Test
    public void shouldRefuseAMisspeltName_whileParkedAwaitingApproval() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        dispatcher.setApprovalModeProvider(() -> true);

        Map<String, Object> response = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBankng.id"),
                "description", "a misspelt name awaiting approval"));

        assertEquals("BULK_VALIDATION_FAILED", errorOf(response).get("code"));
        assertEquals(5, refusedOperationIndex(response));
        assertTrue("the refusal must name the label: " + response,
                String.valueOf(response).contains("coreBankng"));
    }

    /** And the well-formed one is parked as a proposal, reporting no container, exactly as before. */
    @Test
    public void shouldResolveANameWhileParkedAwaitingApproval() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        dispatcher.setApprovalModeProvider(() -> true);

        Map<String, Object> parked = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", nonDegenerateOperations("$coreBanking.id"),
                "description", "a good name awaiting approval")));

        assertTrue("the parked response must declare itself a proposal rather than state",
                parked.containsKey("proposal") || parked.containsKey("preview"));
        for (Map<String, Object> op : operationsOfResult(parked)) {
            assertNull("nothing has been written, so no operation may claim a container: " + op,
                    op.get("parentViewObjectId"));
        }
    }

    // ---- positional behaviour is untouched -------------------------------------------------------

    /**
     * The same non-degenerate payload written the old way still behaves the old way. Held beside
     * the named pins rather than left to the existing corpus, because roughly two thirds of that
     * corpus references index 0, where nothing distinguishes the three framings.
     */
    @Test
    public void shouldLeaveThePositionalFormExactlyAsItWas() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> op : nonDegenerateOperations("$2.id")) {
            Map<String, Object> stripped = new LinkedHashMap<>(op);
            stripped.remove("as");
            ops.add(stripped);
        }

        List<Map<String, Object>> results = operationsOf(invokeTool(registry, "bulk-mutate",
                Map.of("operations", ops, "description", "the positional form, unlabelled")));

        assertEquals("a position must still name the operation at that position",
                results.get(2).get("entityId"), results.get(5).get("parentViewObjectId"));
    }

    /** A forward-pointing name is refused with the same ruling a forward position gets. */
    @Test
    public void shouldRefuseANameDeclaredLaterInTheCall() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = new ArrayList<>(nonDegenerateOperations("$coreBanking.id"));
        ops.set(5, Map.of("tool", "add-to-view", "params", Map.of(
                "viewId", view.getId(), "elementId", "actor-1",
                "parentViewObjectId", "$laterZone.id",
                "x", 30, "y", 30, "width", 120, "height", 55)));
        ops.add(Map.of("tool", "add-group-to-view", "as", "laterZone", "params", Map.of(
                "viewId", view.getId(), "label", "Later Zone",
                "x", 20, "y", 460, "width", 300, "height", 200)));

        String rendered = String.valueOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", ops, "description", "a name declared after it is used")));

        assertTrue("a forward reference must still say it is forward: " + rendered,
                rendered.contains("references a future operation"));
        assertTrue("and name it the way the caller wrote it: " + rendered,
                rendered.contains("$laterZone.id"));
    }

    /**
     * The served description must state the consequence, not only the refusal.
     *
     * <p>An agent reading "an unrecognised key is refused" beside "with continueOnError: true, valid
     * operations execute and failed ones are reported separately" would reasonably conclude a stray
     * key costs it one operation. It costs it the call. That is confirmed policy rather than an
     * accident — a skipped operation is never sent on, and the accessor keys its back-reference maps
     * by position in the list it does receive — so the description has to carry it.</p>
     *
     * <p>Asserted on the served string rather than the source, and on the whole clause rather than a
     * fragment, because a fragment assertion survives the sentence being split back into two that no
     * longer say the same thing.</p>
     */
    @Test
    public void shouldPublishThatAnUnreadableOperationRefusesTheWholeCall() {
        CommandRegistry registry = registryOverLiveAccessor();
        String description = registry.getToolSpecifications().stream()
                .filter(spec -> spec.tool().name().equals("bulk-mutate"))
                .findFirst().orElseThrow().tool().description();

        assertTrue("the unrecognised-key case must sit in the same enumeration as the other "
                + "unreadable shapes, or the 'no flag' promise does not reach it: " + description,
                description.contains("carrying a top-level key that is not tool, params or as"));
        assertTrue("and the enumeration must still say no flag makes such an operation runnable",
                description.contains("no flag makes such an operation runnable"));
        assertTrue("naming continueOnError explicitly, since that is the flag a caller would "
                + "otherwise expect to apply", description.contains("continueOnError does not apply"));
        assertTrue("and giving the reason, which is what stops someone relaxing it later",
                description.contains("every later $N.id would come to mean a different operation"));
    }

    // ---- helpers specific to this class ---------------------------------------------------------

    /** The container a finished view reports for one object, read back through the read path. */
    @SuppressWarnings("unchecked")
    private String readBackParentOf(CommandRegistry registry, String viewObjectId)
            throws Exception {
        Map<String, Object> contents = resultOf(invokeTool(registry, "get-view-contents",
                Map.of("viewId", view.getId())));
        for (String bucket : List.of("visualMetadata", "groups", "notes")) {
            Object rows = contents.get(bucket);
            if (rows instanceof List<?> list) {
                for (Object row : list) {
                    Map<String, Object> entry = (Map<String, Object>) row;
                    if (viewObjectId.equals(entry.get("viewObjectId"))) {
                        return (String) entry.get("parentViewObjectId");
                    }
                }
            }
        }
        throw new AssertionError("no object " + viewObjectId + " in " + contents);
    }

    /** How many groups the view holds, for asserting that a refused operation created none. */
    private int groupCount() {
        int groups = 0;
        for (Object child : view.getChildren()) {
            if (child instanceof com.archimatetool.model.IDiagramModelGroup) {
                groups++;
            }
        }
        return groups;
    }

    /** Ids of every actor placement sitting directly on the view, which is where a drop lands. */
    private List<String> topLevelActorIds(CommandRegistry registry) {
        List<String> ids = new ArrayList<>();
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelObject box
                    && !(child instanceof com.archimatetool.model.IDiagramModelGroup)) {
                ids.add(box.getId());
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> envelope) {
        Object error = envelope.get("error");
        assertNotNull("expected a refusal, got: " + envelope, error);
        return (Map<String, Object>) error;
    }

    /**
     * The error code on the first row of an all-or-nothing refusal.
     *
     * <p>Read from the row rather than from the envelope: the envelope carries the whole-call code,
     * and asserting that alone would pass for any refusal at all — including the one a payload with
     * a completely different defect earns.</p>
     */
    /**
     * The 0-based index of the operation an all-or-nothing refusal blames.
     *
     * <p>Read from the row when there is more than one failure and from {@code details} when there
     * is exactly one — the {@code failed} array is deliberately absent in the single-failure case,
     * so a helper that only read the array would make every one-defect payload untestable here.</p>
     */
    private static int refusedOperationIndex(Map<String, Object> envelope) {
        Map<String, Object> error = errorOf(envelope);
        Object rows = error.get("failed");
        if (rows instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) list.get(0);
            return ((Number) first.get("index")).intValue();
        }
        String details = String.valueOf(error.get("details"));
        int at = details.indexOf("failedOperationIndex=");
        assertTrue("the refusal must say which operation it blames: " + envelope, at >= 0);
        return Integer.parseInt(details.substring(at + "failedOperationIndex=".length())
                .split("[^0-9]")[0]);
    }

    /** The per-operation rows an all-or-nothing refusal carries. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> failedRowsOf(Map<String, Object> envelope) {
        Object rows = errorOf(envelope).get("failed");
        assertTrue("the refusal must carry its per-operation rows: " + envelope,
                rows instanceof List<?> list && !list.isEmpty());
        return (List<Map<String, Object>>) rows;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> failedOf(Map<String, Object> result) {
        Object failed = result.get("failed");
        return failed == null ? List.of() : (List<Map<String, Object>>) failed;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String addTopLevel(CommandRegistry registry, String elementId) throws Exception {
        return (String) viewObjectOf(invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", elementId,
                "x", 0, "y", 0, "width", 400, "height", 300))).get("viewObjectId");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viewObjectOf(Map<String, Object> envelope) {
        return (Map<String, Object>) resultOf(envelope).get("viewObject");
    }

    private static List<Map<String, Object>> operationsOf(Map<String, Object> envelope) {
        return operationsOfResult(resultOf(envelope));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operationsOfResult(Map<String, Object> result) {
        Object ops = result.containsKey("operations") ? result.get("operations")
                : result.get("succeeded");
        return (List<Map<String, Object>>) ops;
    }

    private static String json(Map<String, Object> envelope) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
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
