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
                            double scale, boolean inline, String outputDirectory) {
        // Validate output directory before rendering (fail fast — don't waste CPU)
        if (!inline) {
            validateOutputDirectory(outputDirectory);
        }

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
            filePath = writeToFile(pngBytes, diagramModel.getId(), "png", outputDirectory);
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
                            double scale, boolean inline, String outputDirectory) {
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

    /**
     * Validates the output directory before rendering. If the directory exists,
     * checks writability. If it doesn't exist, checks that parent is writable.
     * Skips validation for temp directory (null/blank outputDirectory).
     */
    private static void validateOutputDirectory(String outputDirectory) {
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return; // temp dir — validated during writeToFile
        }
        java.nio.file.Path dir = java.nio.file.Path.of(outputDirectory);
        if (dir.toFile().exists()) {
            if (!dir.toFile().canWrite()) {
                throw new ModelAccessException(
                        "Output directory is not writable: " + dir,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide a writable directory path or omit outputDirectory to use the temp directory",
                        null);
            }
        } else {
            // Directory doesn't exist — check nearest existing ancestor for writability
            java.nio.file.Path ancestor = dir.getParent();
            while (ancestor != null && !ancestor.toFile().exists()) {
                ancestor = ancestor.getParent();
            }
            if (ancestor != null && !ancestor.toFile().canWrite()) {
                throw new ModelAccessException(
                        "Output directory is not writable: " + dir
                                + " (parent " + ancestor + " is not writable)",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide a writable directory path or omit outputDirectory to use the temp directory",
                        null);
            }
        }
    }

    private static String writeToFile(byte[] data, String viewId, String extension,
            String outputDirectory) {
        java.nio.file.Path exportDir;
        if (outputDirectory == null || outputDirectory.isBlank()) {
            exportDir = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "archi-mcp-export");
        } else {
            exportDir = java.nio.file.Path.of(outputDirectory);
        }

        try {
            java.nio.file.Files.createDirectories(exportDir);
        } catch (IOException e) {
            throw new ModelAccessException(
                    "Failed to create output directory: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }

        if (!exportDir.toFile().canWrite()) {
            throw new ModelAccessException(
                    "Output directory is not writable: " + exportDir,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a writable directory path or omit outputDirectory to use the temp directory",
                    null);
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
