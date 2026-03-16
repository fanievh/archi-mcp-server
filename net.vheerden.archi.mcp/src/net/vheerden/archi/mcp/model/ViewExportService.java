package net.vheerden.archi.mcp.model;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.diagram.util.DiagramUtils;
import com.archimatetool.model.IArchimateDiagramModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ExportViewResultDto;

/**
 * Handles view export to PNG and SVG formats.
 *
 * <p>Extracted from ArchiModelAccessorImpl (Story 12-4) to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class ViewExportService {

    private ViewExportService() {}

    static ExportResult renderPng(IArchimateDiagramModel diagramModel,
                            double scale, boolean inline) {
        long startTime = System.currentTimeMillis();
        AtomicReference<byte[]> pngBytesRef = new AtomicReference<>();
        AtomicReference<Integer> widthRef = new AtomicReference<>();
        AtomicReference<Integer> heightRef = new AtomicReference<>();
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();

        Display.getDefault().syncExec(() -> {
            Image image = null;
            try {
                image = DiagramUtils.createImage(diagramModel, scale, 10);
                ImageData imageData = image.getImageData();
                widthRef.set(imageData.width);
                heightRef.set(imageData.height);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageLoader loader = new ImageLoader();
                loader.data = new ImageData[] { imageData };
                loader.save(baos, SWT.IMAGE_PNG);
                pngBytesRef.set(baos.toByteArray());
            } catch (Throwable t) {
                errorRef.set(new RuntimeException(t));
            } finally {
                if (image != null) {
                    image.dispose();
                }
            }
        });

        if (errorRef.get() != null) {
            throw new ModelAccessException(
                    "PNG rendering failed: " + errorRef.get().getMessage(),
                    errorRef.get(), ErrorCode.INTERNAL_ERROR);
        }

        long renderTimeMs = System.currentTimeMillis() - startTime;
        byte[] pngBytes = pngBytesRef.get();
        String filePath = null;

        if (!inline) {
            filePath = writeToTempFile(pngBytes, diagramModel.getId(), "png");
        }

        ExportViewResultDto metadata = new ExportViewResultDto(
                diagramModel.getId(),
                diagramModel.getName(),
                "png",
                "image/png",
                widthRef.get(),
                heightRef.get(),
                filePath,
                renderTimeMs);

        return new ExportResult(metadata, inline ? pngBytes : null, null);
    }

    static ExportResult renderSvg(IArchimateDiagramModel diagramModel,
                            double scale, boolean inline) {
        if (Platform.getBundle("com.archimatetool.export.svg") == null) {
            throw new ModelAccessException(
                    "SVG export is not available. The SVG export plugin "
                            + "(com.archimatetool.export.svg) is not installed.",
                    ErrorCode.FORMAT_NOT_AVAILABLE,
                    "Install the Archi SVG export plugin or use format 'png' instead",
                    "Use export-view with format 'png'",
                    null);
        }

        // TODO: Implement headless SVG rendering when Archi provides a
        // GraphicalViewer-free SVG export API (follow-up story).
        throw new ModelAccessException(
                "SVG export is not yet supported. Use format 'png' instead.",
                ErrorCode.FORMAT_NOT_AVAILABLE,
                null,
                "Use export-view with format 'png'",
                null);
    }

    static String writeToTempFile(byte[] data, String viewId, String extension) {
        java.nio.file.Path exportDir = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "archi-mcp-export");
        try {
            java.nio.file.Files.createDirectories(exportDir);
        } catch (IOException e) {
            throw new ModelAccessException(
                    "Failed to create export directory: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }
        String fileName = viewId + "_" + System.currentTimeMillis() + "." + extension;
        File outputFile = exportDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(data);
        } catch (IOException e) {
            throw new ModelAccessException(
                    "Failed to write export file: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }
        return outputFile.getAbsolutePath();
    }
}
