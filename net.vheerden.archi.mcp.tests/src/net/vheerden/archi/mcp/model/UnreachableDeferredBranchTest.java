package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;

/**
 * Two conditional branches that cannot be reached on a deferred arm, made load-bearing.
 *
 * <h2>Why a pin rather than a sentence</h2>
 *
 * <p>Both tools below carry a conditional {@code nextSteps} step that reads like every other one
 * this codebase has been rescoping: an ordinary {@code if} over a DTO field, silent on the queued
 * and awaiting-approval arms. Writing a deferred wording for either would be dead code, and worse
 * than dead — a sentence describing a state the caller cannot be in reads as a description of
 * something that happened.</p>
 *
 * <p>What makes it dead is control flow rather than prose, and control flow is a claim. Each
 * branch's selector is produced only on a path that returns <em>above</em> both the approval gate
 * and the queue, so a response carrying it is neither batched nor a proposal by construction. That
 * is asserted here against the real accessor, driven through a real batch and a real approval
 * gate — and each assertion is paired with a positive control on the same fixture, because a probe
 * that cannot observe a deferred arm at all would report every arm as absent and be believed.</p>
 */
public class UnreachableDeferredBranchTest {

    private static final String SESSION = "unreachable-deferred-branch-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private boolean approvalMode;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        approvalMode = false;

        model = factory.createArchimateModel();
        model.setName("Unreachable Deferred Branch Fixture");
        model.setId("model-unreachable-deferred-branch");
        model.setDefaults();

        stubModelManager.setModels(List.of(model));

        dispatcher = new MutationDispatcher(() -> model) {
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
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- optimize-group-order: "the order is already optimal" -----------------------------------

    /**
     * A reorder that finds nothing to do returns above the queue and above the approval gate.
     *
     * <p>The accessor builds one placement plus one container re-fit per <em>reordered</em> group,
     * so an empty {@code reorderedGroups} means an empty command list, and an empty command list
     * returns a plain result before either deferral is considered. The already-optimal step is
     * therefore unreachable on both deferred arms, and no deferred wording for it is written.</p>
     */
    @Test
    public void optimizeGroupOrderWithNothingToReorderIsNeitherBatchedNorAProposal() {
        // Each arm gets its own view. A reorder that lands changes the order it measured, so an
        // arm reading a view an earlier arm already optimised would find nothing to do for the
        // wrong reason.
        String queuedView = alreadyOptimalFixture("queued");
        String proposedView = alreadyOptimalFixture("proposed");

        dispatcher.beginBatch(SESSION, "a reorder with nothing to reorder");
        MutationResult<OptimizeGroupOrderResultDto> queued = optimize(queuedView);
        dispatcher.endBatch(SESSION, false);

        assertEquals("the fixture must actually find nothing to reorder, or it proves nothing "
                + "about the branch this test is about", 0, queued.entity().groupsOptimized());
        assertFalse("a reorder with no commands returns above the queue, so the already-optimal "
                + "step cannot reach a batched response", queued.isBatched());

        approvalMode = true;
        MutationResult<OptimizeGroupOrderResultDto> proposed = optimize(proposedView);
        approvalMode = false;

        assertEquals("the same fixture, the same verdict", 0, proposed.entity().groupsOptimized());
        assertFalse("and it returns above the approval gate too, so the step cannot reach an "
                + "awaiting-approval response", proposed.isProposal());
    }

    /**
     * The positive control: the same batch and the same gate DO defer a reorder that has work.
     *
     * <p>Without this, a fixture that failed to reach the accessor at all — a view the lookup
     * rejects, a batch that was never open — would report "neither batched nor a proposal" for
     * every call and the pin above would certify nothing.</p>
     */
    @Test
    public void aReorderWithWorkToDoIsBatchedAndCanBeAProposal() {
        String queuedView = crossedFixture("queued");
        String proposedView = crossedFixture("proposed");

        dispatcher.beginBatch(SESSION, "a reorder with work to do");
        MutationResult<OptimizeGroupOrderResultDto> queued = optimize(queuedView);
        dispatcher.endBatch(SESSION, false);

        assertTrue("the control fixture must actually reorder something",
                queued.entity().groupsOptimized() > 0);
        assertTrue("and the batch this test opens must really defer it — otherwise the pin above "
                + "is measuring a probe that cannot see a queue", queued.isBatched());

        approvalMode = true;
        MutationResult<OptimizeGroupOrderResultDto> proposed = optimize(proposedView);
        approvalMode = false;

        assertTrue("the same for the approval gate", proposed.entity().groupsOptimized() > 0);
        assertTrue("the gate must really store a proposal", proposed.isProposal());
    }

    // ---- create-specialization: "the specialization already existed" ----------------------------

    /**
     * A duplicate create returns above the queue and above the approval gate.
     *
     * <p>{@code created: false} is written on exactly one path: an existing profile is found and
     * returned with a no-op command, and the accessor short-circuits on that command before
     * either deferral is considered. So the already-existed step cannot appear on a batched or
     * awaiting-approval response and no deferred wording for it is written.</p>
     *
     * <p>Worth stating outright, because it is easy to read the opposite: "the batched arm" and
     * "called while a batch is open" are not the same set for this tool. A duplicate create issued
     * inside an open batch comes back in the applied-arm envelope — no batch wrapper, no queue
     * position — because nothing was queued. That is correct, and it is exactly why the branch is
     * unreachable.</p>
     */
    @Test
    public void aDuplicateCreateSpecializationIsNeitherBatchedNorAProposal() {
        accessor.createSpecialization(SESSION, "Cloud Server", "Node", null);

        dispatcher.beginBatch(SESSION, "a duplicate create inside an open batch");
        MutationResult<java.util.Map<String, Object>> duplicate =
                accessor.createSpecialization(SESSION, "Cloud Server", "Node", null);
        dispatcher.endBatch(SESSION, false);

        assertEquals("the fixture must actually be a duplicate, or it proves nothing about the "
                + "branch this test is about", Boolean.FALSE, duplicate.entity().get("created"));
        assertFalse("a duplicate returns on the no-op path, above the queue, so the "
                + "already-existed step cannot reach a batched response", duplicate.isBatched());

        approvalMode = true;
        MutationResult<java.util.Map<String, Object>> proposedDuplicate =
                accessor.createSpecialization(SESSION, "Cloud Server", "Node", null);
        approvalMode = false;

        assertEquals("the same fixture, the same verdict",
                Boolean.FALSE, proposedDuplicate.entity().get("created"));
        assertFalse("and above the approval gate too, so it cannot reach an awaiting-approval "
                + "response", proposedDuplicate.isProposal());
    }

    /** The positive control: a create that is NOT a duplicate is deferred by the same batch. */
    @Test
    public void aNewCreateSpecializationIsBatchedAndCanBeAProposal() {
        dispatcher.beginBatch(SESSION, "a new create inside an open batch");
        MutationResult<java.util.Map<String, Object>> queued =
                accessor.createSpecialization(SESSION, "Edge Node", "Node", null);
        dispatcher.endBatch(SESSION, false);

        assertEquals("the control must actually create something",
                Boolean.TRUE, queued.entity().get("created"));
        assertTrue("and the batch this test opens must really defer it — otherwise the pin above "
                + "is measuring a probe that cannot see a queue", queued.isBatched());

        approvalMode = true;
        MutationResult<java.util.Map<String, Object>> proposed =
                accessor.createSpecialization(SESSION, "Core Node", "Node", null);
        approvalMode = false;

        assertEquals("the same for the approval gate",
                Boolean.TRUE, proposed.entity().get("created"));
        assertTrue("the gate must really store a proposal", proposed.isProposal());
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private MutationResult<OptimizeGroupOrderResultDto> optimize(String viewId) {
        return accessor.optimizeGroupOrder(SESSION, viewId, "column", 40, 10,
                null, null, false, null, null);
    }

    private IArchimateDiagramModel newView(String suffix) {
        IArchimateDiagramModel v = factory.createArchimateDiagramModel();
        v.setId("view-" + suffix);
        v.setName("Ordered " + suffix);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(v);
        return v;
    }

    /** Two groups whose cross-connections are already in the order that minimises crossings. */
    private String alreadyOptimalFixture(String suffix) {
        // Parallel, so there is nothing to uncross: A->X, B->Y, C->Z.
        return sixNodeFixture(suffix, new String[]{"X", "Y", "Z"});
    }

    /** The same two groups, cross-connected so the minimiser has crossings to remove. */
    private String crossedFixture(String suffix) {
        // Deliberately crossed: A->Z, B->Y, C->X.
        return sixNodeFixture(suffix, new String[]{"Z", "Y", "X"});
    }

    /** Three nodes per group, wired left-to-right in the order {@code targets} names. */
    private String sixNodeFixture(String suffix, String[] targets) {
        IArchimateDiagramModel v = newView(suffix);
        IDiagramModelGroup left = group(v, "Left", suffix, 0, 0, 700, 700);
        IDiagramModelGroup right = group(v, "Right", suffix, 900, 0, 700, 700);

        java.util.Map<String, IDiagramModelArchimateObject> nodes = new java.util.LinkedHashMap<>();
        int y = 40;
        for (String name : List.of("A", "B", "C")) {
            nodes.put(name, node(left, name, suffix, 20, y, 120, 55));
            y += 100;
        }
        y = 40;
        for (String name : List.of("X", "Y", "Z")) {
            nodes.put(name, node(right, name, suffix, 20, y, 120, 55));
            y += 100;
        }

        String[] sources = {"A", "B", "C"};
        for (int i = 0; i < sources.length; i++) {
            connect(nodes.get(sources[i]), nodes.get(targets[i]),
                    "rel-" + suffix + "-" + sources[i] + targets[i]);
        }
        return v.getId();
    }

    private IDiagramModelGroup group(IArchimateDiagramModel v, String name, String suffix,
            int x, int y, int w, int h) {
        IDiagramModelGroup g = factory.createDiagramModelGroup();
        g.setId("grp-" + suffix + "-" + name);
        g.setName(name);
        g.setBounds(x, y, w, h);
        v.getChildren().add(g);
        return g;
    }

    private IDiagramModelArchimateObject node(IDiagramModelContainer parent, String name,
            String suffix, int x, int y, int w, int h) {
        INode concept = factory.createNode();
        concept.setId("node-" + suffix + "-" + name);
        concept.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("vo-" + suffix + "-" + name);
        obj.setArchimateConcept(concept);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    private void connect(IDiagramModelArchimateObject source, IDiagramModelArchimateObject target,
            String id) {
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(source.getArchimateElement());
        rel.setTarget(target.getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId("conn-" + id);
        conn.setArchimateConcept(rel);
        conn.connect(source, target);
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
}
