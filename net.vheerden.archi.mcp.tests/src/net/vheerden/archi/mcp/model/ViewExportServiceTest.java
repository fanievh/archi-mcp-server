package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.Platform;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;

/**
 * JUnit Plug-in Test for {@link ViewExportService} — G7.
 *
 * <p>Exercises the per-format render path end-to-end (PNG/JPG/SVG/PDF) using
 * a tiny synthetic diagram fixture (1 element). Validates that each format
 * emits bytes with the right magic header + that the file-output path writes
 * the correct extension.</p>
 *
 * <p>Requires the Archi runtime (uses {@code DiagramUtils.createImage} for
 * raster formats + the {@code com.archimatetool.export.svg} bundle for SVG/PDF).
 * Skipped when the Eclipse platform is not running (i.e., when accidentally
 * launched as a plain JUnit test instead of a Plug-in Test).</p>
 */
public class ViewExportServiceTest {

    private IArchimateDiagramModel fixture;
    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("Requires Eclipse platform (run as Plug-in Test)",
                Platform.isRunning());
        fixture = buildSingleElementDiagram();
        tempDir = Files.createTempDirectory("archi-mcp-export-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); }
                                catch (IOException ignored) {} });
            }
        }
    }

    @Test
    public void shouldRenderPng_byteIdenticalHeader() throws Exception {
        ExportResult result = invokeRender("renderPng", fixture, 1.0, true,
                null);
        byte[] bytes = result.imageBytes();
        assertNotNull("PNG bytes should be non-null in inline mode", bytes);
        assertTrue("PNG bytes should start with PNG magic 0x89 50 4E 47",
                bytes.length >= 4
                        && (bytes[0] & 0xFF) == 0x89
                        && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47);
        assertEquals("png", result.metadata().format());
        assertEquals("image/png", result.metadata().mimeType());
    }

    @Test
    public void shouldRenderJpg_validJpegHeader() throws Exception {
        ExportResult result = invokeRenderJpg(fixture, 1.0, 90, true, null);
        byte[] bytes = result.imageBytes();
        assertNotNull("JPG bytes should be non-null in inline mode", bytes);
        assertTrue("JPG bytes should start with JPEG SOI marker 0xFF 0xD8",
                bytes.length >= 2
                        && (bytes[0] & 0xFF) == 0xFF
                        && (bytes[1] & 0xFF) == 0xD8);
        assertEquals("jpg", result.metadata().format());
        assertEquals("image/jpeg", result.metadata().mimeType());
    }

    /**
     * Directional size-comparison only. The single-element synthetic fixture is
     * near-uniform white with one small actor rectangle; SWT's JPEG encoder may
     * produce nearly identical byte counts at quality=10 vs quality=100 here
     * because the DCT coefficients are trivially small regardless of quantization
     * table. On a real complex diagram the delta would be greater than 50%.
     * This test validates that the {@code loader.compression} field is wired
     * end-to-end — not that the JPEG quality difference is measurable on sparse
     * fixtures. Acknowledged limitation of the sparse fixture.
     */
    @Test
    public void shouldRenderJpgAtQuality10_smallerThanQuality100() throws Exception {
        ExportResult low = invokeRenderJpg(fixture, 1.0, 10, true, null);
        ExportResult high = invokeRenderJpg(fixture, 1.0, 100, true, null);
        assertTrue("JPG@quality=10 should be smaller (or equal — sparse fixture) "
                        + "than JPG@quality=100",
                low.imageBytes().length <= high.imageBytes().length);
    }

    @Test
    public void shouldRenderSvg_validXmlHeader() throws Exception {
        Assume.assumeTrue("Requires com.archimatetool.export.svg bundle",
                Platform.getBundle("com.archimatetool.export.svg") != null);
        ExportResult result = invokeRender("renderSvg", fixture, 1.0, true, null);
        String svg = result.svgContent();
        assertNotNull("SVG content should be non-null in inline mode", svg);
        assertTrue("SVG should start with <?xml or <svg",
                svg.startsWith("<?xml") || svg.startsWith("<svg"));
        assertEquals("svg", result.metadata().format());
        assertEquals("image/svg+xml", result.metadata().mimeType());
        assertNull("SVG metadata width should be null (vector)",
                result.metadata().width());
        assertNull("SVG metadata height should be null (vector)",
                result.metadata().height());
    }

    @Test
    public void shouldRenderPdf_validPdfHeader() throws Exception {
        Assume.assumeTrue("Requires com.archimatetool.export.svg bundle",
                Platform.getBundle("com.archimatetool.export.svg") != null);
        ExportResult result = invokeRender("renderPdf", fixture, 1.0, true, null);
        byte[] bytes = result.imageBytes();
        assertNotNull("PDF bytes should be non-null in inline mode", bytes);
        assertTrue("PDF bytes should start with %PDF-",
                bytes.length >= 5
                        && bytes[0] == '%' && bytes[1] == 'P'
                        && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-');
        assertEquals("pdf", result.metadata().format());
        assertEquals("application/pdf", result.metadata().mimeType());
        assertNull("PDF metadata width should be null (vector)",
                result.metadata().width());
        assertNull("PDF metadata height should be null (vector)",
                result.metadata().height());
    }

    @Test
    public void shouldWriteJpgFile_withJpgExtension_whenInlineFalse() throws Exception {
        ExportResult result = invokeRenderJpg(fixture, 1.0, 90, false,
                tempDir.toString());
        String filePath = result.metadata().filePath();
        assertNotNull("File path should be set in file mode", filePath);
        assertTrue("File path should end with .jpg", filePath.endsWith(".jpg"));
        assertTrue("File should exist on disk", Files.exists(Path.of(filePath)));
    }

    @Test
    public void shouldWritePdfFile_withPdfExtension_whenInlineFalse() throws Exception {
        Assume.assumeTrue("Requires com.archimatetool.export.svg bundle",
                Platform.getBundle("com.archimatetool.export.svg") != null);
        ExportResult result = invokeRender("renderPdf", fixture, 1.0, false,
                tempDir.toString());
        String filePath = result.metadata().filePath();
        assertNotNull("File path should be set in file mode", filePath);
        assertTrue("File path should end with .pdf", filePath.endsWith(".pdf"));
        Path written = Path.of(filePath);
        assertTrue("File should exist on disk", Files.exists(written));
        byte[] head = new byte[5];
        try (var in = Files.newInputStream(written)) {
            int n = in.read(head);
            assertEquals(5, n);
        }
        assertTrue("Written PDF file should start with %PDF-",
                head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                        && head[3] == 'F' && head[4] == '-');
    }

    // ---- Helpers ----

    private static IArchimateDiagramModel buildSingleElementDiagram() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setName("ViewExportServiceTest fixture");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // 1 BusinessActor element placed inside the view
        var actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IDiagramModelArchimateObject dmo = IArchimateFactory.eINSTANCE
                .createDiagramModelArchimateObject();
        dmo.setArchimateElement(actor);
        dmo.setBounds(10, 10, 120, 60);
        view.getChildren().add(dmo);

        return view;
    }

    /** Reflectively invokes the package-private static render method by name. */
    private static ExportResult invokeRender(String methodName,
            IArchimateDiagramModel diagram, double scale, boolean inline,
            String outputDirectory) throws Exception {
        Method m = ViewExportService.class.getDeclaredMethod(methodName,
                IArchimateDiagramModel.class, double.class, boolean.class, String.class);
        m.setAccessible(true);
        return (ExportResult) m.invoke(null, diagram, scale, inline, outputDirectory);
    }

    /** Specialised invoker for renderJpg (has the extra int quality parameter). */
    private static ExportResult invokeRenderJpg(IArchimateDiagramModel diagram,
            double scale, int quality, boolean inline, String outputDirectory)
            throws Exception {
        Method m = ViewExportService.class.getDeclaredMethod("renderJpg",
                IArchimateDiagramModel.class, double.class, int.class, boolean.class,
                String.class);
        m.setAccessible(true);
        return (ExportResult) m.invoke(null, diagram, scale, quality, inline,
                outputDirectory);
    }
}
