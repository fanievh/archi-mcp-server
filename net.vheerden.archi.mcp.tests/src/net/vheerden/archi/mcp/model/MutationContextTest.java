package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Tests for {@link MutationContext} approval and proposal management.
 *
 * <p>MutationContext is package-private, so this test class resides in the
 * same package within the test fragment. Uses a StubCommand for proposal
 * construction.</p>
 */
public class MutationContextTest {

    private MutationContext context;

    @Before
    public void setUp() {
        context = new MutationContext();
    }

    // ---- Approval flag tests removed: the approval bit is no longer per-session ----
    // It is a single global, human-owned switch read via ApprovalModeProvider in
    // MutationDispatcher (see MutationDispatcherTest). MutationContext stores only proposals.

    // ---- Proposal storage tests ----

    @Test
    public void shouldStoreAndRetrieveProposal() {
        PendingProposal proposal = makeProposal("create-element", "Create Actor");
        String id = context.storeProposal(proposal);

        assertNotNull(id);
        assertTrue(id.startsWith("p-"));

        PendingProposal stored = context.getProposal(id);
        assertNotNull(stored);
        assertEquals(id, stored.proposalId());
        assertEquals("create-element", stored.tool());
        assertEquals("Create Actor", stored.description());
    }

    @Test
    public void shouldAssignIncrementingProposalIds() {
        String id1 = context.storeProposal(makeProposal("create-element", "desc1"));
        String id2 = context.storeProposal(makeProposal("create-element", "desc2"));
        String id3 = context.storeProposal(makeProposal("create-element", "desc3"));

        assertEquals("p-1", id1);
        assertEquals("p-2", id2);
        assertEquals("p-3", id3);
    }

    @Test
    public void shouldRemoveProposal() {
        String id = context.storeProposal(makeProposal("create-element", "desc"));

        PendingProposal removed = context.removeProposal(id);

        assertNotNull(removed);
        assertEquals(id, removed.proposalId());
        assertNull(context.getProposal(id));
    }

    @Test
    public void shouldReturnNullForNonExistentProposal() {
        assertNull(context.getProposal("p-999"));
        assertNull(context.removeProposal("p-999"));
    }

    @Test
    public void shouldListPendingProposals() {
        context.storeProposal(makeProposal("create-element", "desc1"));
        context.storeProposal(makeProposal("create-relationship", "desc2"));

        List<PendingProposal> pending = context.getPendingProposals();

        assertEquals(2, pending.size());
        assertEquals("create-element", pending.get(0).tool());
        assertEquals("create-relationship", pending.get(1).tool());
    }

    @Test
    public void shouldClearProposals() {
        context.storeProposal(makeProposal("create-element", "desc1"));
        context.storeProposal(makeProposal("create-element", "desc2"));
        assertEquals(2, context.getPendingCount());

        context.clearProposals();

        assertEquals(0, context.getPendingCount());
        assertTrue(context.getPendingProposals().isEmpty());
    }

    @Test
    public void shouldPreserveProposalsAcrossBatchReset() {
        context.storeProposal(makeProposal("create-element", "desc"));

        // Simulate batch cycle
        context.beginBatch("test batch");
        context.reset();

        // Pending proposals should survive batch commit/rollback
        assertEquals(1, context.getPendingCount());
    }

    @Test
    public void shouldThrowWhenMaxProposalsReached() {
        for (int i = 0; i < MutationContext.MAX_PENDING_PROPOSALS; i++) {
            context.storeProposal(makeProposal("create-element", "desc-" + i));
        }

        try {
            context.storeProposal(makeProposal("create-element", "one too many"));
            fail("Expected IllegalStateException for max proposals");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Maximum pending proposals reached"));
        }
    }

    // ---- batch intent ----

    @Test
    public void shouldRecordBatchIntent_distinctFromDescription_andClearOnReset() {
        assertNull(context.getBatchIntent());
        context.beginBatch("undo-history label", "Wire the fraud-check path into checkout");
        // Intent is kept separate from the undo-history description — never merged.
        assertEquals("Wire the fraud-check path into checkout", context.getBatchIntent());
        context.reset();
        assertNull("reset clears batch intent", context.getBatchIntent());
    }

    @Test
    public void shouldLeaveBatchIntentNull_whenBeginBatchHasNoIntent() {
        context.beginBatch("undo-history label");
        assertNull(context.getBatchIntent());
    }

    @Test
    public void shouldPreserveEffectAndIntent_whenStoringProposal() {
        PendingProposal p = new PendingProposal(
                null, "create-relationship", "Create ServingRelationship: id-src → id-tgt",
                new StubCommand("x"), "entity", null, Map.of(), "Valid", Instant.now(),
                "Create ServingRelationship: 'A' → 'B'", "Wire the fraud-check path");
        String id = context.storeProposal(p);

        PendingProposal stored = context.getProposal(id);
        assertEquals(id, stored.proposalId());
        assertEquals("Create ServingRelationship: 'A' → 'B'", stored.effectDescription());
        assertEquals("Wire the fraud-check path", stored.intent());
    }

    @Test
    public void shouldLeaveEffectAndIntentNull_forBackCompatProposal() {
        String id = context.storeProposal(makeProposal("create-element", "Create Foo"));
        PendingProposal stored = context.getProposal(id);
        assertNull(stored.effectDescription());
        assertNull(stored.intent());
    }

    // ---- TTL expiry predicate + sweep ----

    @Test
    public void shouldExpire_whenOlderThanTtl() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = created.plus(java.time.Duration.ofMinutes(31));
        assertTrue(MutationContext.isExpired(created, now, java.time.Duration.ofMinutes(30)));
    }

    @Test
    public void shouldNotExpire_whenWithinTtl() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = created.plus(java.time.Duration.ofMinutes(29));
        assertFalse(MutationContext.isExpired(created, now, java.time.Duration.ofMinutes(30)));
    }

    @Test
    public void shouldTreatNullCreatedAt_asNotExpired() {
        assertFalse(MutationContext.isExpired(null, Instant.now(), java.time.Duration.ofMinutes(30)));
    }

    @Test
    public void shouldSweepExpiredProposals_keepingFreshOnes() {
        Instant now = Instant.now();
        String oldId = context.storeProposal(
                makeProposalAt("create-element", "old", now.minus(java.time.Duration.ofHours(1))));
        String freshId = context.storeProposal(
                makeProposalAt("create-element", "fresh", now));

        List<String> swept = context.sweepExpired(now, java.time.Duration.ofMinutes(30));

        assertEquals("only the abandoned proposal is swept", List.of(oldId), swept);
        assertNull("expired proposal removed", context.getProposal(oldId));
        assertNotNull("fresh proposal retained", context.getProposal(freshId));
    }

    // ---- Commit summary reports skips nested inside a queued compound ----

    @Test
    public void commitSummary_reportsASkipNestedInsideAQueuedCompound() {
        // A force delete-specialization is queued as ONE compound whose declining command is a
        // leaf. A top-level-only scan would miss it and report a silent success; the summary must
        // name it. (Uses a plain CompoundCommand — the production NonNotifyingCompoundCommand
        // cannot execute headless.)
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IBusinessActor concept = factory.createBusinessActor();
        concept.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(concept);
        IProfile p1 = makeProfile(factory, "P1");
        IProfile p2 = makeProfile(factory, "P2");
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        CompoundCommand op = new CompoundCommand("Delete specialization: P1");
        op.add(new ClearSpecializationCommand(concept, Set.of(p1))); // declines: p2 unauthorised

        context.beginBatch("batch");
        context.queueCommand(op, "Delete specialization: P1");
        op.execute(); // simulate dispatch — the leaf declines and records its reason

        BatchSummaryDto summary = context.buildCommitSummary();

        assertNotNull("A skip nested in a queued compound must surface", summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The skip is paired with the operation's own description",
                summary.skippedOperations().get(0).startsWith("Delete specialization: P1 — "));
    }

    @Test
    public void commitSummary_reportsOneLine_whenAQueuedOperationDeclinesInSeveralPlaces() {
        // A force delete-specialization that declines coherently: its clear AND its profile-removal
        // both refuse. That is ONE queued operation and must be ONE skippedOperations line, or the
        // count no longer reconciles against what the caller queued.
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IProfile victim = makeProfile(factory, "VIP");
        model.getProfiles().add(victim);
        IBusinessActor concept = factory.createBusinessActor();
        concept.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(concept);
        concept.getProfiles().add(victim);

        CompoundCommand op = new CompoundCommand("Delete specialization: VIP");
        op.add(new ClearSpecializationCommand(concept, Set.of(victim)));
        op.add(new DeleteProfileCommand(victim, model));

        // Co-queued attach defeats the guard, so both leaves decline:
        IProfile other = makeProfile(factory, "Internal");
        model.getProfiles().add(other);
        concept.getProfiles().add(other);

        context.beginBatch("batch");
        context.queueCommand(op, "Delete specialization: VIP");
        op.execute();

        BatchSummaryDto summary = context.buildCommitSummary();

        assertNotNull(summary.skippedOperations());
        assertEquals("A coherent multi-leaf decline is one operation, so one line",
                1, summary.skippedOperations().size());
        assertTrue("Both reasons are joined into the single line",
                summary.skippedOperations().get(0).contains("; "));
    }

    @Test
    public void commitSummary_hasNoSkippedOperations_whenEveryQueuedCommandRan() {
        context.beginBatch("batch");
        context.queueCommand(new StubCommand("op"), "op");

        BatchSummaryDto summary = context.buildCommitSummary();

        assertNull("Nothing declined, so the field is omitted", summary.skippedOperations());
    }

    private static IProfile makeProfile(IArchimateFactory factory, String name) {
        IProfile p = factory.createProfile();
        p.setName(name);
        p.setConceptType("BusinessActor");
        return p;
    }

    // ---- Helpers ----

    private PendingProposal makeProposal(String tool, String description) {
        return new PendingProposal(
                null, tool, description, new StubCommand(description),
                "entity", null, Map.of("key", "value"), "Valid", Instant.now());
    }

    private PendingProposal makeProposalAt(String tool, String description, Instant createdAt) {
        return new PendingProposal(
                null, tool, description, new StubCommand(description),
                "entity", null, Map.of("key", "value"), "Valid", createdAt);
    }

    // ---- queuedContainer: resolving a not-yet-executed container by id ----

    /** A bare queued group add is found by the id its command will create. */
    @Test
    public void shouldResolveQueuedGroup_whenItsAddIsTopLevelInTheQueue() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel model = f.createArchimateModel();
        model.setDefaults();
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-queued");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(group, view), "add group");

        assertEquals("the queued group resolves by its own id",
                group, context.queuedContainer("grp-queued"));
        assertNull("an unrelated id resolves to nothing", context.queuedContainer("grp-other"));
        assertNull("a null id resolves to nothing", context.queuedContainer(null));
    }

    /** Adds are routinely wrapped, so the walk must descend into compounds — and nested ones. */
    @Test
    public void shouldResolveQueuedGroup_whenItsAddIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-wrapped");
        group.setBounds(0, 0, 100, 100);

        CompoundCommand inner = new CompoundCommand("inner wrap");
        inner.add(new AddGroupToViewCommand(group, view));
        CompoundCommand outer = new CompoundCommand("outer wrap");
        outer.add(new StubCommand("unrelated"));
        outer.add(inner);

        context.beginBatch("b");
        context.queueCommand(outer, "wrapped add");

        assertEquals("a doubly-wrapped add must still resolve",
                group, context.queuedContainer("grp-wrapped"));
    }

    /**
     * Only the two container-creating command types match. A queued note carries a real id but is
     * not a valid parent, so it must resolve to nothing and leave the caller's live lookup — and its
     * ordinary not-found error — in charge.
     */
    @Test
    public void shouldNotResolveQueuedNote_whenItsIdIsRequestedAsAContainer() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var note = f.createDiagramModelNote();
        note.setId("note-queued");
        note.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddNoteToViewCommand(note, view), "add note");

        assertNull("a note is never a container", context.queuedContainer("note-queued"));
    }

    /** The queue is the whole lifetime: reset (commit or rollback) makes the id unresolvable. */
    @Test
    public void shouldStopResolvingQueuedGroup_whenTheBatchIsReset() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-transient");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(group, view), "add group");
        assertNotNull("pre-condition: resolvable while queued",
                context.queuedContainer("grp-transient"));

        context.reset();

        assertNull("a reset batch leaves nothing addressable",
                context.queuedContainer("grp-transient"));
    }

    // ---- queuedViewObject: resolving a not-yet-executed object by id ----

    /**
     * The update lookup admits every kind an add can create, and reports the container the queued
     * command will attach it to — the half the parent-fit cascade needs, since a detached object's
     * {@code eContainer()} is null.
     */
    @Test
    public void shouldResolveQueuedObjectAndItsParent_whenAnyAddKindIsQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-any");
        group.setBounds(0, 0, 100, 100);
        var note = f.createDiagramModelNote();
        note.setId("note-any");
        note.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(group, view), "add group");
        context.queueCommand(new AddNoteToViewCommand(note, group), "add note");

        assertEquals("a queued group resolves as an update target",
                group, context.queuedViewObject("grp-any").object());
        assertEquals("its destined parent comes back with it",
                view, context.queuedViewObject("grp-any").parent());
        assertEquals("a queued note resolves as an update target too",
                note, context.queuedViewObject("note-any").object());
        assertEquals("nested under the queued group",
                group, context.queuedViewObject("note-any").parent());
        assertNull("an unrelated id resolves to nothing", context.queuedViewObject("nope"));
        assertNull("a null id resolves to nothing", context.queuedViewObject(null));
    }

    /**
     * The two lookups share one walk but not one answer: a queued note is a valid update target and
     * an invalid nesting parent. Widening the walk must never widen what may be a parent.
     */
    @Test
    public void shouldSeparateTargetKindsFromParentKinds_whenTheSameNoteIsQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var note = f.createDiagramModelNote();
        note.setId("note-split");
        note.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddNoteToViewCommand(note, view), "add note");

        assertNotNull("resolvable as an update target", context.queuedViewObject("note-split"));
        assertNull("never resolvable as a parent", context.queuedContainer("note-split"));
    }

    /** Adds are routinely wrapped, so this walk must descend into compounds as its sibling does. */
    @Test
    public void shouldResolveQueuedObject_whenItsAddIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var note = f.createDiagramModelNote();
        note.setId("note-wrapped");
        note.setBounds(0, 0, 100, 100);

        CompoundCommand inner = new CompoundCommand("inner wrap");
        inner.add(new AddNoteToViewCommand(note, view));
        CompoundCommand outer = new CompoundCommand("outer wrap");
        outer.add(new StubCommand("unrelated"));
        outer.add(inner);

        context.beginBatch("b");
        context.queueCommand(outer, "wrapped add");

        assertEquals("a doubly-wrapped add must still resolve",
                note, context.queuedViewObject("note-wrapped").object());
    }

    /** Outside batch mode nothing resolves, which is what keeps every non-batch caller unchanged. */
    @Test
    public void shouldNotResolveQueuedObject_whenTheBatchIsResetOrNeverStarted() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var note = f.createDiagramModelNote();
        note.setId("note-transient");
        note.setBounds(0, 0, 100, 100);

        assertNull("no batch open, nothing resolves", context.queuedViewObject("note-transient"));

        context.beginBatch("b");
        context.queueCommand(new AddNoteToViewCommand(note, view), "add note");
        assertNotNull("pre-condition: resolvable while queued",
                context.queuedViewObject("note-transient"));

        context.reset();

        assertNull("a reset batch leaves nothing addressable",
                context.queuedViewObject("note-transient"));
    }

    // ---- queuedCreatedView: resolving a not-yet-created view by id ----

    /**
     * A view a queued {@code create-view} will make is resolvable by the id the agent was handed,
     * which is what lets a later add in the same batch name it as its target.
     */
    @Test
    public void shouldResolveQueuedView_whenItsCreateIsQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        var view = f.createArchimateDiagramModel();
        view.setId("view-queued");

        context.beginBatch("b");
        context.queueCommand(new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)),
                "create view");

        assertSame("the queued view resolves by its id",
                view, context.queuedCreatedView("view-queued"));
        assertNull("an unrelated id resolves to nothing", context.queuedCreatedView("nope"));
        assertNull("a null id resolves to nothing", context.queuedCreatedView(null));
    }

    /** {@code clone-view} queues its create inside a compound, so the walk must descend. */
    @Test
    public void shouldResolveQueuedView_whenItsCreateIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        var view = f.createArchimateDiagramModel();
        view.setId("view-wrapped");

        CompoundCommand inner = new CompoundCommand("inner wrap");
        inner.add(new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)));
        CompoundCommand outer = new CompoundCommand("outer wrap");
        outer.add(new StubCommand("unrelated"));
        outer.add(inner);

        context.beginBatch("b");
        context.queueCommand(outer, "wrapped create");

        assertSame("a doubly-wrapped create must still resolve",
                view, context.queuedCreatedView("view-wrapped"));
    }

    /**
     * A created view is a model object, not a view object, so it must not leak into the lookups that
     * answer about things an {@code add-*-to-view} builds — nor they into it.
     */
    @Test
    public void shouldKeepCreatedViewsOutOfTheViewObjectLookups_whenBothAreQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        var view = f.createArchimateDiagramModel();
        view.setId("view-mixed");
        var group = f.createDiagramModelGroup();
        group.setId("grp-mixed");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)),
                "create view");
        context.queueCommand(new AddGroupToViewCommand(group, view), "add group");

        assertNull("a created view is not an add-created view object",
                context.queuedViewObject("view-mixed"));
        assertNull("nor may it ever be treated as a nesting container",
                context.queuedContainer("view-mixed"));
        assertNull("and a queued view object is not a created view",
                context.queuedCreatedView("grp-mixed"));
    }

    /** Outside batch mode nothing resolves, which is what keeps every non-batch caller unchanged. */
    @Test
    public void shouldNotResolveQueuedView_whenTheBatchIsResetOrNeverStarted() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        var view = f.createArchimateDiagramModel();
        view.setId("view-transient");

        assertNull("no batch open, nothing resolves", context.queuedCreatedView("view-transient"));

        context.beginBatch("b");
        context.queueCommand(new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)),
                "create view");
        assertNotNull("pre-condition: resolvable while queued",
                context.queuedCreatedView("view-transient"));

        context.reset();

        assertNull("a reset batch leaves nothing addressable",
                context.queuedCreatedView("view-transient"));
    }

    // ---- queuedCreatedElement: resolving a not-yet-created element by id ----

    /**
     * An element a queued {@code create-element} will make is resolvable by the id the agent was
     * handed, which is what lets a later {@code add-to-view} in the same batch place it.
     */
    @Test
    public void shouldResolveQueuedElement_whenItsCreateIsQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-queued");

        context.beginBatch("b");
        context.queueCommand(new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)),
                "create element");

        assertSame("the queued element resolves by its id",
                actor, context.queuedCreatedElement("actor-queued"));
        assertNull("an unrelated id resolves to nothing", context.queuedCreatedElement("nope"));
        assertNull("a null id resolves to nothing", context.queuedCreatedElement(null));
    }

    /** A specialized create wraps its element create in a compound, so the walk must descend. */
    @Test
    public void shouldResolveQueuedElement_whenItsCreateIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-wrapped");

        CompoundCommand wrap = new CompoundCommand("create specialized element");
        wrap.add(new StubCommand("apply specialization"));
        wrap.add(new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)));

        context.beginBatch("b");
        context.queueCommand(wrap, "wrapped create");

        assertSame("a wrapped create must still resolve",
                actor, context.queuedCreatedElement("actor-wrapped"));
    }

    /**
     * The two create lookups stay typed: a queued view is not an element and a queued element is
     * not a view, so naming either in the other's slot must resolve to nothing rather than to an
     * object of the wrong kind.
     */
    @Test
    public void shouldNotCrossResolve_whenBothCreateKindsAreQueued() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        var view = f.createArchimateDiagramModel();
        view.setId("view-typed");
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-typed");

        context.beginBatch("b");
        context.queueCommand(new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)),
                "create view");
        context.queueCommand(new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)),
                "create element");

        assertSame("each resolves in its own slot", view, context.queuedCreatedView("view-typed"));
        assertSame("each resolves in its own slot",
                actor, context.queuedCreatedElement("actor-typed"));
        assertNull("a queued element is not a view", context.queuedCreatedView("actor-typed"));
        assertNull("a queued view is not an element", context.queuedCreatedElement("view-typed"));
    }

    /** Outside batch mode nothing resolves, which is what keeps every non-batch caller unchanged. */
    @Test
    public void shouldNotResolveQueuedElement_whenTheBatchIsResetOrNeverStarted() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-transient");

        assertNull("no batch open, nothing resolves",
                context.queuedCreatedElement("actor-transient"));

        context.beginBatch("b");
        context.queueCommand(new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)),
                "create element");
        assertNotNull("pre-condition: resolvable while queued",
                context.queuedCreatedElement("actor-transient"));

        context.reset();

        assertNull("a reset batch leaves nothing addressable",
                context.queuedCreatedElement("actor-transient"));
    }

    // ---- queuedParents: the whole-queue containment an ancestor walk climbs ----

    /**
     * The map answers for every queued object at once, including ones nested under other queued
     * objects — the chain a parent-fit cascade has to climb. It reports containment only; whether an
     * ancestor may be grown is the caller's test, which is why a note appears here yet is still
     * never a nesting parent.
     */
    @Test
    public void shouldMapEveryQueuedObjectToItsDestinedParent_whenABatchIsOpen() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var outer = f.createDiagramModelGroup();
        outer.setId("grp-outer");
        outer.setBounds(0, 0, 200, 200);
        var inner = f.createDiagramModelGroup();
        inner.setId("grp-inner");
        inner.setBounds(0, 0, 100, 100);
        var note = f.createDiagramModelNote();
        note.setId("note-leaf");
        note.setBounds(0, 0, 50, 50);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(outer, view), "add outer");
        context.queueCommand(new AddGroupToViewCommand(inner, outer), "add inner");
        context.queueCommand(new AddNoteToViewCommand(note, inner), "add note");

        var parents = context.queuedParents();
        assertEquals("the outermost queued group is destined for the view",
                view, parents.get("grp-outer"));
        assertEquals("the inner group is destined for the outer one",
                outer, parents.get("grp-inner"));
        assertEquals("every add kind is mapped, not just containers",
                inner, parents.get("note-leaf"));
        assertNull("an unqueued id maps to nothing", parents.get("grp-nope"));
        assertNull("and containment is not permission to be a parent",
                context.queuedContainer("note-leaf"));
    }

    /** Outside batch mode there is no queue to derive containment from, so there is no map. */
    @Test
    public void shouldReturnNullQueuedParents_whenNoBatchIsOpen() {
        assertNull("no batch, no queued containment", context.queuedParents());
    }

    /** Adds are routinely wrapped, so this reader descends into compounds like its siblings. */
    @Test
    public void shouldMapQueuedParents_whenTheAddIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-wrapped-parent");
        group.setBounds(0, 0, 100, 100);

        CompoundCommand inner = new CompoundCommand("inner wrap");
        inner.add(new AddGroupToViewCommand(group, view));
        CompoundCommand outer = new CompoundCommand("outer wrap");
        outer.add(new StubCommand("unrelated"));
        outer.add(inner);

        context.beginBatch("b");
        context.queueCommand(outer, "wrapped add");

        assertEquals("a wrapped add still declares its destined parent",
                view, context.queuedParents().get("grp-wrapped-parent"));
    }

    /** An empty batch has a map, not a null — the batch is open, it simply queued nothing yet. */
    @Test
    public void shouldReturnEmptyQueuedParents_whenBatchIsOpenButNothingIsQueued() {
        context.beginBatch("b");

        assertNotNull("an open batch always yields a map", context.queuedParents());
        assertTrue("with nothing in it", context.queuedParents().isEmpty());
    }

    /** Reset clears the queue, so the derived containment disappears with it. */
    @Test
    public void shouldStopMappingQueuedParents_whenTheBatchIsReset() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var group = f.createDiagramModelGroup();
        group.setId("grp-transient-parent");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(group, view), "add group");
        assertEquals("pre-condition: mapped while queued",
                view, context.queuedParents().get("grp-transient-parent"));

        context.reset();

        assertNull("a reset batch derives no containment", context.queuedParents());
    }

    /**
     * The queued containment can never contain a cycle, so the ancestor walk that reads it always
     * terminates. Measured rather than argued: an object's destined parent is fixed when its add is
     * queued, and the only ids nameable then are ones already queued, so every parent edge points
     * strictly backwards in queue order. Asserted directly over a three-deep chain.
     */
    @Test
    public void shouldPointEveryParentEdgeBackwards_soQueuedContainmentCannotCycle() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var outer = f.createDiagramModelGroup();
        outer.setId("cyc-outer");
        outer.setBounds(0, 0, 200, 200);
        var mid = f.createDiagramModelGroup();
        mid.setId("cyc-mid");
        mid.setBounds(0, 0, 150, 150);
        var inner = f.createDiagramModelGroup();
        inner.setId("cyc-inner");
        inner.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(outer, view), "add outer");
        context.queueCommand(new AddGroupToViewCommand(mid, outer), "add mid");
        context.queueCommand(new AddGroupToViewCommand(inner, mid), "add inner");

        Map<String, IDiagramModelContainer> parents = context.queuedParents();
        List<String> queueOrder = List.of("cyc-outer", "cyc-mid", "cyc-inner");
        for (var entry : parents.entrySet()) {
            String parentId = entry.getValue().getId();
            int childIndex = queueOrder.indexOf(entry.getKey());
            int parentIndex = queueOrder.indexOf(parentId);
            assertTrue("every queued parent must be queued strictly before its child (or not queued"
                    + " at all): " + entry.getKey() + " -> " + parentId,
                    parentIndex < childIndex);
        }
        // Walking up from the innermost therefore terminates outside the map, at the view.
        assertNull("the outermost group's parent is the view, which is not itself queued",
                parents.get(parents.get("cyc-outer").getId()));
    }

    /**
     * The whole-queue map and the single-id lookup must resolve a repeated id the same way, or the
     * cascade's first hop and its later hops could disagree about who a child belongs to. Not
     * constructible through the tool surface — every add mints a fresh id — so it is pinned here at
     * the level where it can be constructed at all.
     */
    @Test
    public void shouldKeepTheFirstQueuedAdd_whenTwoQueuedCommandsShareAnId() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var host = f.createDiagramModelGroup();
        host.setId("dup-host");
        host.setBounds(0, 0, 300, 300);
        var first = f.createDiagramModelGroup();
        first.setId("dup-id");
        first.setBounds(0, 0, 100, 100);
        var second = f.createDiagramModelGroup();
        second.setId("dup-id");
        second.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(host, view), "add host");
        context.queueCommand(new AddGroupToViewCommand(first, view), "add first");
        context.queueCommand(new AddGroupToViewCommand(second, host), "add second");

        assertEquals("the map keeps the first queued add's parent",
                view, context.queuedParents().get("dup-id"));
        assertEquals("and the single-id lookup agrees with it",
                view, context.queuedViewObject("dup-id").parent());
        assertSame("both readers return the same object", first, context.queuedViewObject("dup-id").object());
    }

    // ---- queuedBounds: the geometry a later prepare of the same batch measures against ----

    /**
     * The map answers for every object the batch has queued a bounds write for, whichever command
     * carries it. An object the batch only <em>added</em> is deliberately absent: its add already
     * configured the bounds it will attach, so the caller's ordinary read is correct for it.
     */
    @Test
    public void shouldMapEveryQueuedBoundsWrite_whenABatchIsOpen() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var view = f.createArchimateDiagramModel();
        var added = f.createDiagramModelGroup();
        added.setId("grp-added-only");
        added.setBounds(0, 0, 200, 200);
        var resized = f.createDiagramModelGroup();
        resized.setId("grp-resized");
        resized.setBounds(10, 10, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new AddGroupToViewCommand(added, view), "add");
        context.queueCommand(new UpdateViewObjectCommand(resized, 20, 30, 400, 500), "resize");

        Map<String, int[]> bounds = context.queuedBounds();
        assertArrayEquals("the queued write is reported verbatim",
                new int[] { 20, 30, 400, 500 }, bounds.get("grp-resized"));
        assertNull("an object the batch only added is absent — its own bounds are already right",
                bounds.get("grp-added-only"));
        assertNull("an unqueued id maps to nothing", bounds.get("grp-nope"));
    }

    /**
     * <strong>The last write wins</strong> — the opposite of {@code queuedParents}'s first-match
     * rule, and the single most reversible mistake in this reader. An id is created once so its
     * parent cannot change, but its bounds can be rewritten repeatedly, and the write that survives
     * commit is the one that executes last.
     */
    @Test
    public void shouldKeepTheLastQueuedWrite_whenOneObjectIsResizedTwice() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var group = f.createDiagramModelGroup();
        group.setId("grp-twice");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new UpdateViewObjectCommand(group, 0, 0, 300, 300), "first resize");
        context.queueCommand(new UpdateViewObjectCommand(group, 0, 0, 800, 800), "second resize");

        assertArrayEquals("the later queued write is the one that will survive commit",
                new int[] { 0, 0, 800, 800 }, context.queuedBounds().get("grp-twice"));
    }

    /**
     * A cascade's group resizes ride inside the compound of the update that provoked them, and the
     * spacing and routing passes bundle theirs the same way, so this reader descends into compounds
     * exactly as its siblings do.
     */
    @Test
    public void shouldMapQueuedBounds_whenTheWriteIsNestedInsideCompounds() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var group = f.createDiagramModelGroup();
        group.setId("grp-wrapped-resize");
        group.setBounds(0, 0, 100, 100);

        CompoundCommand inner = new CompoundCommand("inner wrap");
        inner.add(new UpdateViewObjectCommand(group, 5, 5, 640, 640));
        CompoundCommand outer = new CompoundCommand("outer wrap");
        outer.add(new StubCommand("unrelated"));
        outer.add(inner);

        context.beginBatch("b");
        context.queueCommand(outer, "wrapped resize");

        assertArrayEquals("a wrapped bounds write is still visible",
                new int[] { 5, 5, 640, 640 }, context.queuedBounds().get("grp-wrapped-resize"));
    }

    /** Outside batch mode there is no queue to derive geometry from, so there is no map. */
    @Test
    public void shouldReturnNullQueuedBounds_whenNoBatchIsOpen() {
        assertNull("no batch, no queued geometry", context.queuedBounds());
    }

    /** An empty batch has a map, not a null — the batch is open, it simply queued nothing yet. */
    @Test
    public void shouldReturnEmptyQueuedBounds_whenBatchIsOpenButNothingIsQueued() {
        context.beginBatch("b");

        assertNotNull("an open batch always yields a map", context.queuedBounds());
        assertTrue("with nothing in it", context.queuedBounds().isEmpty());
    }

    /** Reset clears the queue on both commit and rollback, so the derived geometry goes with it. */
    @Test
    public void shouldStopMappingQueuedBounds_whenTheBatchIsReset() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        var group = f.createDiagramModelGroup();
        group.setId("grp-transient-resize");
        group.setBounds(0, 0, 100, 100);

        context.beginBatch("b");
        context.queueCommand(new UpdateViewObjectCommand(group, 0, 0, 300, 300), "resize");
        assertArrayEquals("pre-condition: mapped while queued",
                new int[] { 0, 0, 300, 300 }, context.queuedBounds().get("grp-transient-resize"));

        context.reset();

        assertNull("a reset batch derives no geometry", context.queuedBounds());
    }

    private static class StubCommand extends Command {
        StubCommand(String label) {
            super(label);
        }

        @Override
        public void execute() {
            // no-op for testing
        }
    }
}
