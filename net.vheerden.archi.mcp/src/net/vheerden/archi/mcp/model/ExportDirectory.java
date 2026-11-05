package net.vheerden.archi.mcp.model;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Validates and prepares the directory an exported view is written to, and writes the file.
 *
 * <p>The error code follows where the remedy lies. A directory the caller named that cannot be
 * used — it is a file or a broken symbolic link, it sits under one, files cannot be created in it,
 * or creating it fails — is the caller's to fix, so it is answered as
 * {@link ErrorCode#INVALID_PARAMETER} with a correction. So is a path the platform cannot parse,
 * and so is an access denial when the file is written into a directory the caller named. Any
 * other write failure (a full disk, an I/O error) is not in the caller's input and stays
 * {@link ErrorCode#INTERNAL_ERROR}. The default temp directory failing in any way is the host's
 * fault: the caller chose nothing, so it stays {@link ErrorCode#INTERNAL_ERROR}, with no
 * correction to offer.</p>
 *
 * <p>"Files can be created in it" means writable and searchable. On Windows
 * {@link java.io.File#canWrite()} and {@link java.io.File#canExecute()} report true for any
 * existing directory whatever its access control list says, so a denial there is not seen by
 * {@link #validate} and is caught only when the file is written.</p>
 *
 * <p>Kept free of SWT and EMF so the rule can be exercised without a running workbench.</p>
 */
final class ExportDirectory {

    static final String CORRECTION =
            "Provide a writable directory path or omit outputDirectory to use the temp directory";

    private ExportDirectory() {
    }

    /**
     * Refuses a caller-named directory that cannot be used, before any rendering is spent on it.
     * A relative path is judged against the working directory it resolves against. A {@code ..}
     * that follows a name which does not exist yet is refused where the system cannot follow it
     * (it resolves {@code ..} through the name before it, as POSIX systems do). A blank or absent directory means the temp directory, which is checked by
     * {@link #prepare}.
     */
    static void validate(String outputDirectory) {
        if (!isCallerNamed(outputDirectory)) {
            return; // temp dir — validated during prepare
        }
        Path dir = callerPath(outputDirectory);
        // Not following links, so a broken link counts as existing and fails the directory check
        // below, which does follow them: a link to a usable directory is a usable directory.
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (!dir.toFile().isDirectory()) {
                throw new ModelAccessException(
                        "Output directory is not a directory: " + dir,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        CORRECTION,
                        null);
            }
            if (!dir.toFile().canWrite() || !dir.toFile().canExecute()) {
                throw new ModelAccessException(
                        "Output directory is not writable: " + dir,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        CORRECTION,
                        null);
            }
        } else {
            // Directory doesn't exist — check the nearest existing ancestor. A writable FILE passes a
            // writability check (a character device such as /dev/null does), yet nothing can be
            // created beneath it, so it must also be a directory.
            Path ancestor = nearestExisting(dir);
            if (ancestor != null && !ancestor.toFile().isDirectory()) {
                throw ancestorNotADirectory(dir, ancestor);
            }
            if (ancestor != null && (!ancestor.toFile().canWrite() || !ancestor.toFile().canExecute())) {
                throw new ModelAccessException(
                        "Output directory is not writable: " + dir
                                + " (parent " + ancestor + " is not writable)",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        CORRECTION,
                        null);
            }
            if (ancestor != null) {
                refuseDotDotAfterMissing(dir, ancestor);
            }
        }
    }

    private static ModelAccessException ancestorNotADirectory(Path dir, Path ancestor) {
        return new ModelAccessException(
                "Output directory is not a directory: " + dir
                        + " (" + ancestor + " is not a directory)",
                ErrorCode.INVALID_PARAMETER,
                null,
                CORRECTION,
                null);
    }

    /**
     * Refuses {@code dir}, which does not exist, if a {@code ..} after {@code ancestor}, its
     * nearest existing ancestor, steps back out of a name that does not exist.
     */
    private static void refuseDotDotAfterMissing(Path dir, Path ancestor) {
        // The names after the ancestor do not exist yet. A '..' among them steps back out of
        // one that does not exist, which a system resolving '..' through the name before it
        // cannot follow; one that collapses '..' first (Windows) can, so the system is asked.
        // Taken with subpath, not relativize, which would normalize the '..' away.
        Path abs = dir.toAbsolutePath();
        Path rest = abs.subpath(ancestor.getNameCount(), abs.getNameCount());
        Path missing = ancestor.resolve(rest.getName(0));
        for (Path name : rest) {
            if ("..".equals(name.toString())
                    && !Files.exists(missing.resolve(".."), LinkOption.NOFOLLOW_LINKS)) {
                throw new ModelAccessException(
                        "Output directory is not a valid path: " + dir
                                + " (" + missing
                                + " does not exist, so '..' after it cannot be followed)",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        CORRECTION,
                        null);
            }
        }
    }

    /**
     * Creates the export directory if needed and returns it: the caller's {@code outputDirectory},
     * or {@code archi-mcp-export} under {@code java.io.tmpdir} when none is given. Refuses one that
     * files cannot be created in. A {@code ..} out of a name that no longer exists (it can
     * disappear while the view renders) is refused before anything is created, because creating
     * would make the normalized path, which the caller never named; a removal between that check
     * and the creation is not caught.
     */
    static Path prepare(String outputDirectory) {
        return prepare(outputDirectory, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** As {@link #prepare(String)}, with the temp directory's base given explicitly. */
    static Path prepare(String outputDirectory, Path tempBase) {
        boolean callerNamed = isCallerNamed(outputDirectory);
        Path exportDir;
        if (!callerNamed) {
            exportDir = tempBase.resolve("archi-mcp-export");
        } else {
            exportDir = callerPath(outputDirectory);
            // Checked again here: the name before a '..' can be removed while the view renders,
            // and creating the directory would then make the normalized path instead. Where the
            // ancestor is a file, creating fails below and keeps the file-system cause. A broken
            // link does not fail there (creating steps past it to the normalized path), so it is
            // refused as validation refuses it.
            if (!Files.exists(exportDir, LinkOption.NOFOLLOW_LINKS)) {
                Path ancestor = nearestExisting(exportDir);
                if (ancestor != null && Files.isSymbolicLink(ancestor) && !Files.exists(ancestor)) {
                    throw ancestorNotADirectory(exportDir, ancestor);
                }
                if (ancestor != null && ancestor.toFile().isDirectory()) {
                    refuseDotDotAfterMissing(exportDir, ancestor);
                }
            }
        }

        try {
            Files.createDirectories(exportDir);
        } catch (IOException e) {
            // Validation cannot foresee everything (a race, a denial deeper than the nearest
            // existing ancestor), so the caller's directory failing here is still the caller's.
            if (callerNamed) {
                throw new ModelAccessException(
                        "Failed to create output directory: " + e.getMessage(),
                        e, ErrorCode.INVALID_PARAMETER, null, CORRECTION, null);
            }
            throw new ModelAccessException(
                    "Failed to create output directory: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }

        if (!exportDir.toFile().canWrite() || !exportDir.toFile().canExecute()) {
            if (!callerNamed) {
                throw new ModelAccessException(
                        "Output directory is not writable: " + exportDir,
                        ErrorCode.INTERNAL_ERROR);
            }
            throw new ModelAccessException(
                    "Output directory is not writable: " + exportDir,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    CORRECTION,
                    null);
        }
        return exportDir;
    }

    /**
     * The nearest ancestor of {@code dir} that exists, as an absolute path, or null if none does.
     * Walked from the absolute path so a relative name is judged by the working directory it
     * resolves against; a link counts as existing even when it is broken. The walk is lexical
     * ({@code m/..} is not collapsed), while a POSIX system resolves {@code ..} through the name
     * before it; that is safe because {@link #validate} and {@link #prepare} refuse any {@code ..}
     * the walk steps past that the system cannot follow.
     */
    static Path nearestExisting(Path dir) {
        Path ancestor = dir.toAbsolutePath().getParent();
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        return ancestor;
    }

    /**
     * Writes {@code data} to {@code fileName} in a directory returned by {@link #prepare} and
     * returns the file's absolute path. The write is the net for a denial no check foresaw.
     */
    static Path write(String outputDirectory, Path exportDir, String fileName, byte[] data) {
        Path file = exportDir.resolve(fileName);
        try {
            Files.write(file, data);
        } catch (AccessDeniedException e) {
            // A denial's message is the bare path, so say what went wrong, as the old stream did.
            String message = "Failed to write export file: " + e.getMessage()
                    + (e.getReason() == null ? " (Permission denied)" : "");
            if (isCallerNamed(outputDirectory)) {
                throw new ModelAccessException(
                        message, e, ErrorCode.INVALID_PARAMETER, null, CORRECTION, null);
            }
            throw new ModelAccessException(message, e, ErrorCode.INTERNAL_ERROR);
        } catch (IOException e) {
            throw new ModelAccessException(
                    "Failed to write export file: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }
        return file.toAbsolutePath();
    }

    private static boolean isCallerNamed(String outputDirectory) {
        return outputDirectory != null && !outputDirectory.isBlank();
    }

    /** Parses a caller-named directory; a path the platform rejects is the caller's to fix. */
    private static Path callerPath(String outputDirectory) {
        try {
            return Path.of(outputDirectory);
        } catch (InvalidPathException e) {
            throw new ModelAccessException(
                    "Output directory is not a valid path: " + e.getMessage(),
                    e, ErrorCode.INVALID_PARAMETER, null, CORRECTION, null);
        }
    }
}
