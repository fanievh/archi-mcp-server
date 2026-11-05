package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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

import net.vheerden.archi.mcp.response.ErrorCode;

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
     * The exception itself must travel too. The client is told only the message, so the Archi log
     * is where the stack of a refused connection or a DNS failure is read, and that needs the cause.
     */
    @Test
    public void shouldKeepTheCause_whenBuildingTheDownloadFailure() {
        IOException cause = new IOException("Connection reset");

        ModelAccessException e = ImageDownloadFailure.forCause("https://example.invalid/icon.png", cause);

        assertSame(cause, e.getCause());
    }

    /**
     * The wiring. A closed loopback port refuses immediately, so this reaches the generic arm
     * without network egress and without depending on DNS.
     */
    @Test
    public void shouldNameTheUrl_whenTheRealDownloadFailsToConnect() throws Exception {
        int closedPort;
        // The one deliberate probe-then-close in the test tree: the point is a port nothing listens on.
        // A process that grabs it in between can only make this fail (the fetch is bounded by its connect
        // and request timeouts), never hang. Holding the port bound without listening is not a substitute:
        // on macOS a connect to such a port times out instead of being refused.
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
            assertEquals("an unreachable host is the URL's problem, not the server's",
                    ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertFalse("it must never read as though the URL itself were null: " + e.getMessage(),
                    e.getMessage().contains("URL: null"));
            assertNotNull("a failed import must say what to do about it",
                    e.getSuggestedCorrection());
        }
    }

    // ---- the phase split: a failure of the fetch is the URL's, a failure after it the server's ----

    /** A transport failure (refused, DNS, TLS, reset) raised by the fetch keeps its cause. */
    @Test
    public void shouldAnswerInvalidParameter_whenTheFetchFailsInTransport() {
        IOException reset = new IOException("Connection reset");

        try {
            ImageDownloadFailure.fetch("https://example.invalid/icon.png", () -> {
                throw reset;
            });
            fail("expected the transport failure to be mapped");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertSame(reset, e.getCause());
            assertTrue(e.getMessage().contains("https://example.invalid/icon.png"));
        }
    }

    /**
     * An interrupted fetch is the server cancelling its own work: it stays a server-side answer,
     * keeps its cause (which is what lets the log rule recognise it), and restores the flag.
     */
    @Test
    public void shouldStayInternalAndRestoreTheFlag_whenTheFetchIsInterrupted() {
        InterruptedException interrupted = new InterruptedException("stopping");

        try {
            ImageDownloadFailure.fetch("https://example.invalid/icon.png", () -> {
                throw interrupted;
            });
            fail("expected the interruption to be mapped");
        } catch (ModelAccessException e) {
            assertSame(interrupted, e.getCause());
            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertEquals("Image download interrupted: https://example.invalid/icon.png", e.getMessage());
            assertTrue("the interrupt flag must be restored", Thread.interrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * An interrupt that the body read reports as an IOException is still the server cancelling its
     * own work, not a fault of the URL: it takes the interrupted arm, with an
     * {@link InterruptedException} as the direct cause so the log rule recognises it.
     */
    @Test
    public void shouldTakeTheInterruptedArm_whenAnInterruptArrivesAsAnIoException() {
        IOException wrapped = new IOException(new InterruptedException());

        try {
            ImageDownloadFailure.fetch("https://example.invalid/icon.png", () -> {
                throw wrapped;
            });
            fail("expected the interruption to be mapped");
        } catch (ModelAccessException e) {
            assertTrue("the direct cause must be an InterruptedException: " + e.getCause(),
                    e.getCause() instanceof InterruptedException);
            assertSame("the original exception stays in the chain", wrapped, e.getCause().getCause());
            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertTrue("the interrupt flag must be restored", Thread.interrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * An unchecked failure inside the fetch (a security manager, a client fault) is the server's,
     * and it happened before any bytes arrived: it must not read as a failure to store them.
     */
    @Test
    public void shouldNameTheDownload_whenTheFetchFailsUnchecked() {
        SecurityException denied = new SecurityException("connect denied");

        try {
            ImageDownloadFailure.fetch("https://example.invalid/icon.png", () -> {
                throw denied;
            });
            fail("expected the unchecked failure to be mapped");
        } catch (ModelAccessException e) {
            assertSame(denied, e.getCause());
            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertTrue("it failed downloading, not storing: " + e.getMessage(),
                    e.getMessage().startsWith("Failed to download image from URL: "));
        }
    }

    /**
     * A URL with no host parses as a URI but cannot be requested. It is the caller's to fix, and
     * nothing was downloaded, so it is neither a server fault nor a failure to store.
     */
    @Test
    public void shouldAnswerInvalidParameter_whenTheUrlHasNoHost() {
        try {
            accessor.addImageFromUrl("session", "http:///icon.png");
            fail("expected a URL with no host to be refused");
        } catch (ModelAccessException e) {
            assertEquals("a URL with no host is the caller's to fix: " + e.getMessage(),
                    ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("Malformed URL: http:///icon.png"));
        }
    }

    /**
     * A failure after the bytes arrived (the temp file, the write, storing the image) is the
     * server's own, whatever its type: a full temp disk is an IOException exactly as a reset is.
     */
    @Test
    public void shouldStayInternal_whenTheFailureComesAfterTheFetch() {
        IOException diskFull = new IOException("No space left on device");

        ModelAccessException e = ImageDownloadFailure.afterFetch(
                "https://example.invalid/icon.png", diskFull);

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertSame(diskFull, e.getCause());
        assertTrue("the URL is named: " + e.getMessage(),
                e.getMessage().contains("https://example.invalid/icon.png"));
        assertTrue("the local cause is named: " + e.getMessage(),
                e.getMessage().contains("No space left on device"));
        assertNotNull(e.getSuggestedCorrection());
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
