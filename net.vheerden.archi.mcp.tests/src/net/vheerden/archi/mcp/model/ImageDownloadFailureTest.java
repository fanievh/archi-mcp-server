package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

/**
 * A failed URL import has to say which URL failed.
 *
 * <p>The generic download-failure arm substituted the exception's message <em>for</em> the URL, so
 * when the cause carried no message — a DNS failure does not — the delivered text was
 * {@code "Failed to download image from URL: null"}. That reads as though the caller passed a null
 * URL, which is a different defect entirely, and it names nothing the caller can act on. Every
 * sibling arm in the same method names the URL; only this one did not. It also passed no
 * suggestedCorrection at all.</p>
 *
 * <p>The batch import form makes this sharper rather than creating it: a batch reports one such
 * message per failing entry, so an unattributable message is repeated rather than isolated.</p>
 *
 * <p>Both halves are exercised: the message builder against a cause with no message (deterministic,
 * no I/O), and the real accessor against a closed loopback port, which reaches the generic arm
 * offline and proves the builder is actually wired into it.</p>
 */
public class ImageDownloadFailureTest {

    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;

    @Before
    public void setUp() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Image Download Failure Fixture");
        model.setId("model-image-download-failure");
        model.setDefaults();
        stubModelManager.setModels(List.of(model));

        CommandStack stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(command);
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

    /** The case that produced the bare "null": a cause carrying no message of its own. */
    @Test
    public void shouldNameTheUrl_whenTheCauseCarriesNoMessage() {
        ModelAccessException e = ImageDownloadFailure.forCause(
                "https://example.invalid/icon.png", new java.net.ConnectException());

        assertNotNull(e.getMessage());
        assertTrue("the message must name the URL that failed: " + e.getMessage(),
                e.getMessage().contains("https://example.invalid/icon.png"));
        assertFalse("a cause with no message must not surface as a bare 'null': " + e.getMessage(),
                e.getMessage().contains("null"));
        assertNotNull("a failed import must say what to do about it", e.getSuggestedCorrection());
    }

    /** When the cause does carry a message, it must survive — the URL is added, not substituted. */
    @Test
    public void shouldKeepTheCauseDetail_whenTheCauseCarriesAMessage() {
        ModelAccessException e = ImageDownloadFailure.forCause(
                "https://example.invalid/icon.png", new IOException("Connection reset"));

        assertTrue("the URL must be named: " + e.getMessage(),
                e.getMessage().contains("https://example.invalid/icon.png"));
        assertTrue("the cause must not be discarded: " + e.getMessage(),
                e.getMessage().contains("Connection reset"));
    }

    /**
     * The wiring. A closed loopback port refuses immediately, so this reaches the generic arm
     * without network egress and without depending on DNS.
     */
    @Test
    public void shouldNameTheUrl_whenTheRealDownloadFailsToConnect() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        } // closed on exit — nothing is listening on this port now

        String url = "http://127.0.0.1:" + closedPort + "/icon.png";
        try {
            accessor.addImageFromUrl("session", url);
            fail("expected the download to fail against a closed port");
        } catch (ModelAccessException e) {
            assertTrue("the delivered message must name the URL: " + e.getMessage(),
                    e.getMessage().contains(url));
            assertFalse("it must never read as though the URL itself were null: " + e.getMessage(),
                    e.getMessage().contains("URL: null"));
            assertNotNull("a failed import must say what to do about it",
                    e.getSuggestedCorrection());
        }
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
