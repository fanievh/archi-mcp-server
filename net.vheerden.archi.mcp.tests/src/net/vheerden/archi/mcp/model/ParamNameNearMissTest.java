package net.vheerden.archi.mcp.model;

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
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * The same identifier is spelled several ways across the tool surface — {@code id} on
 * update-element, {@code elementId} on add-to-view and delete-element, {@code objectId} on
 * move-to-folder — and no JSON-schema validator is wired in, so a key spelled a sibling tool's
 * way is dropped in silence. What the caller then reads is that a required parameter is missing,
 * while it is holding that parameter under a name one synonym away.
 *
 * <p>Inside bulk-mutate the cost is not one wasted parameter but one wasted call: the failure is
 * re-wrapped as a whole-call validation failure, so a 150-operation call is resent because one key
 * was spelled wrong. These pins assert the diagnostic where the agent actually meets it — through
 * bulk-mutate end to end, and through the standalone tool — because the two read the parameter on
 * separate code paths and a fix to one does not reach the other.</p>
 *
 * <p>The last pin is the one that keeps the diagnostic honest: when nothing resembling the missing
 * key was supplied, no near miss may be invented.</p>
 */
public class ParamNameNearMissTest {

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actor;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Param Near Miss Fixture");
        model.setId("model-param-near-miss");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Target Actor");
        business.getElements().add(actor);

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

    /**
     * The pair the field actually hit: update-element spells it {@code id}, its sibling add-to-view
     * spells it {@code elementId}. Asserted through bulk-mutate rather than on the helper, because
     * bulk reads the parameter on its own path and the re-wrapped envelope is all the agent sees.
     */
    @Test
    public void shouldNameTheAcceptedSpelling_whenUpdateElementIsGivenElementIdInBulk()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-element",
                        "params", Map.of("elementId", actor.getId(), "name", "Renamed"))),
                "description", "sibling spelling"));

        String message = errorField(envelope, "message");
        String correction = errorField(envelope, "suggestedCorrection");
        assertTrue("the error must name the spelling actually supplied: " + message,
                message.contains("elementId"));
        assertTrue("the error must name the spelling this tool accepts: " + message,
                message.contains("id"));
        assertTrue("the correction must tell the agent what to rename, not just to retry: "
                + correction, correction.contains("elementId") && correction.contains("'id'"));
    }

    /**
     * The same defect from the other direction — add-to-view wants {@code elementId} and an agent
     * arriving from update-element sends {@code id}. Both directions must be covered or the fix is
     * only half a fix.
     */
    @Test
    public void shouldNameTheAcceptedSpelling_whenAddToViewIsGivenIdInBulk() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view",
                        "params", Map.of("viewId", view.getId(), "id", actor.getId()))),
                "description", "sibling spelling, other direction"));

        String message = errorField(envelope, "message");
        String correction = errorField(envelope, "suggestedCorrection");
        assertTrue("the error must name the spelling actually supplied: " + message,
                message.contains("'id'"));
        assertTrue("the error must name the spelling this tool accepts: " + message,
                message.contains("elementId"));
        assertTrue("the correction must name the rename: " + correction,
                correction.contains("elementId"));
    }

    /**
     * The standalone half. Bulk and standalone read the parameter on separate code paths, so a
     * diagnostic added to one reaches neither the other's callers nor its own sibling — leaving a
     * new divergence in place of the old one.
     */
    @Test
    public void shouldNameTheAcceptedSpelling_whenTheStandaloneToolIsGivenTheSiblingSpelling()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "update-element",
                Map.of("elementId", actor.getId(), "name", "Renamed"));

        String message = errorField(envelope, "message");
        String correction = errorField(envelope, "suggestedCorrection");
        assertTrue("the standalone caller must be told the same thing the bulk caller is: "
                + message, message.contains("elementId"));
        assertTrue("the standalone correction must name the rename: " + correction,
                correction.contains("elementId") && correction.contains("'id'"));
    }

    /**
     * The guard against a diagnostic that flatters itself. Supplying nothing resembling the missing
     * key must produce the plain missing-parameter error — a near-miss clause here would be an
     * unverified claim in a field the agent reads as measured fact.
     */
    @Test
    public void shouldNotInventANearMiss_whenNoSimilarKeyWasSupplied() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-element",
                        "params", Map.of("name", "Renamed"))),
                "description", "nothing resembling an id"));

        String message = errorField(envelope, "message");
        assertTrue("the plain missing-parameter error must survive: " + message,
                message.contains("id"));
        assertTrue("no near miss may be reported when none was supplied: " + message,
                !message.contains("which this tool does not accept"));
    }

    /**
     * The one reader that bypasses both shared seams. find-concept-usage reads its identifier with
     * a hand-rolled check of its own, and {@code conceptId} is itself one of the colliding
     * spellings — so without this it would be the single place on the surface where a sibling
     * tool's spelling is met with silence, while the diagnostic table claims to cover the concept.
     */
    @Test
    public void shouldNameTheAcceptedSpelling_whenFindConceptUsageIsGivenTheSiblingSpelling()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "find-concept-usage",
                Map.of("elementId", actor.getId()));

        String message = errorField(envelope, "message");
        String correction = errorField(envelope, "suggestedCorrection");
        assertTrue("the error must name the spelling actually supplied: " + message,
                message.contains("elementId"));
        assertTrue("the error must name the spelling this tool accepts: " + message,
                message.contains("conceptId"));
        assertTrue("the correction must name the rename: " + correction,
                correction.contains("conceptId"));
    }

    /**
     * And the guard for it: with no near miss, this tool keeps its own better correction — which
     * points at the search tools — rather than being flattened to the generic one.
     */
    @Test
    public void shouldKeepItsOwnCorrection_whenFindConceptUsageIsGivenNothingSimilar()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "find-concept-usage", Map.of());

        String correction = errorField(envelope, "suggestedCorrection");
        assertTrue("the tool's own, more useful correction must survive: " + correction,
                correction.contains("search-elements"));
    }

    /**
     * The parity this whole surface turns on, held structurally rather than by enumeration.
     *
     * <p>A standalone caller that omits a required parameter is told what to do about it. A bulk
     * caller was told only to "fix the failed operation and retry the entire bulk-mutate call" —
     * the generic fallback the re-wrap substitutes when an operation supplies no correction of its
     * own. That is strictly less than the standalone caller is told, on the path where it costs
     * most: the retry is the whole call.
     *
     * <p>Driving every supported tool with empty parameters catches the <em>next</em> tool added to
     * the enum, which is the only reason this is worth building rather than asserting the one pair
     * already known. Tools that accept empty parameters, and failures that are not about a missing
     * parameter, are passed over — their corrections are a different subject.</p>
     */
    @Test
    public void shouldNotTellTheBulkCallerLess_whenARequiredParameterIsMissing() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        List<String> underInformative = new ArrayList<>();

        for (String tool : net.vheerden.archi.mcp.response.dto.BulkOperation.SUPPORTED_TOOLS_ORDERED) {
            Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                    "operations", List.of(Map.of("tool", tool, "params", Map.of())),
                    "description", "missing-parameter probe for " + tool));

            if (!(envelope.get("error") instanceof Map<?, ?> error)) {
                continue; // this tool has no required parameter to omit
            }
            String message = String.valueOf(error.get("message"));
            if (!message.contains("Missing required parameter")) {
                continue; // failed for some other reason; a different subject
            }
            String correction = String.valueOf(error.get("suggestedCorrection"));
            if (correction.startsWith("Fix the failed operation")) {
                underInformative.add(tool + " -> " + correction);
            }
        }

        assertTrue("these bulk operations tell the caller less than the standalone tool would, "
                + "falling back to the generic retry-the-whole-call correction: " + underInformative,
                underInformative.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static String errorField(Map<String, Object> envelope, String field) {
        Object error = envelope.get("error");
        if (!(error instanceof Map<?, ?> m)) {
            throw new AssertionError("expected an error envelope, got: " + envelope);
        }
        return String.valueOf(((Map<String, Object>) m).get(field));
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
