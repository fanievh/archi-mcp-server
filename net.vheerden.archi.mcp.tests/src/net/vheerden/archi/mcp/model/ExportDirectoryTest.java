package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The export directory's error codes follow where the remedy lies: a directory the caller named
 * that cannot be used is the caller's to fix, so it is a parameter error with a correction; the
 * default temp directory failing is the host's fault, so it stays an internal error.
 */
public class ExportDirectoryTest {

    private Path root;
    private final List<Path> searchRevoked = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("export-directory-test");
    }

    @After
    public void tearDown() throws IOException {
        if (root == null) {
            return;
        }
        // Files.walk cannot descend a directory without search permission, so give it back first.
        for (Path p : searchRevoked) {
            p.toFile().setReadable(true);
            p.toFile().setWritable(true);
            p.toFile().setExecutable(true);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                p.toFile().setWritable(true);
                p.toFile().delete();
            });
        }
    }

    @Test
    public void shouldRejectAsInvalidParameter_whenOutputDirectoryIsAnExistingFile() throws IOException {
        Path file = Files.createTempFile(root, "not-a-dir", ".txt");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(file.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + file, e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsInvalidParameter_whenAnAncestorIsAFile() throws IOException {
        Path file = Files.createTempFile(root, "blocker", ".txt");
        Path dir = file.resolve("sub").resolve("dir");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + dir + " (" + file + " is not a directory)",
                e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    /** The backstop for whatever validation cannot foresee: reached directly, past validation. */
    @Test
    public void shouldKeepCauseAndAnswerInvalidParameter_whenCreatingACallerDirectoryFails()
            throws IOException {
        Path file = Files.createTempFile(root, "blocker", ".txt");
        Path dir = file.resolve("sub").resolve("dir");

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(dir.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith("Failed to create output directory: "));
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
        assertTrue("the file-system failure is kept as the cause, got " + e.getCause(),
                e.getCause() instanceof IOException);
    }

    @Test
    public void shouldAnswerInternalError_whenTheDefaultTempDirectoryCannotBeCreated() throws IOException {
        Path tempBase = Files.createTempFile(root, "tmpdir-is-a-file", ".txt");

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(null, tempBase));

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith("Failed to create output directory: "));
        assertTrue("the file-system failure is kept as the cause, got " + e.getCause(),
                e.getCause() instanceof IOException);
    }

    @Test
    public void shouldKeepTheSiblingChecksByteIdentical_whenDirectoryIsNotWritable() throws IOException {
        Path locked = Files.createDirectory(root.resolve("locked"));
        File lockedFile = locked.toFile();
        lockedFile.setWritable(false);
        assumeFalse("a root user can write a read-only directory, so it cannot be made unwritable here",
                lockedFile.canWrite());

        ModelAccessException existing = expectRejection(() -> ExportDirectory.validate(locked.toString()));
        assertEquals(ErrorCode.INVALID_PARAMETER, existing.getErrorCode());
        assertEquals("Output directory is not writable: " + locked, existing.getMessage());
        assertEquals("Provide a writable directory path or omit outputDirectory to use the temp directory",
                existing.getSuggestedCorrection());

        Path below = locked.resolve("a").resolve("b");
        ModelAccessException parent = expectRejection(() -> ExportDirectory.validate(below.toString()));
        assertEquals(ErrorCode.INVALID_PARAMETER, parent.getErrorCode());
        assertEquals("Output directory is not writable: " + below + " (parent " + locked + " is not writable)",
                parent.getMessage());
        assertEquals("Provide a writable directory path or omit outputDirectory to use the temp directory",
                parent.getSuggestedCorrection());
    }

    @Test
    public void shouldCreateAndReturnTheDirectory_whenPathIsValid() {
        Path dir = root.resolve("out").resolve("nested");

        ExportDirectory.validate(dir.toString());
        Path prepared = ExportDirectory.prepare(dir.toString(), root);

        assertEquals(dir, prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    @Test
    public void shouldUseTheExportFolderUnderTheTempBase_whenNoDirectoryIsGiven() {
        Path prepared = ExportDirectory.prepare("  ", root);

        assertEquals(root.resolve("archi-mcp-export"), prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    /** A path the platform cannot even parse is still the caller's input, not a server fault. */
    @Test
    public void shouldRejectAsInvalidParameter_whenOutputDirectoryIsNotAValidPath() {
        String unparseable = root + "/a\u0000b";

        ModelAccessException validated = expectRejection(() -> ExportDirectory.validate(unparseable));
        assertEquals(ErrorCode.INVALID_PARAMETER, validated.getErrorCode());
        assertTrue(validated.getMessage(), validated.getMessage().startsWith("Output directory is not a valid path: "));
        assertEquals(ExportDirectory.CORRECTION, validated.getSuggestedCorrection());
        assertTrue("the parse failure is kept as the cause, got " + validated.getCause(),
                validated.getCause() instanceof InvalidPathException);

        ModelAccessException prepared = expectRejection(() -> ExportDirectory.prepare(unparseable, root));
        assertEquals(ErrorCode.INVALID_PARAMETER, prepared.getErrorCode());
    }

    /**
     * The caller named no directory, so a temp directory it cannot write to is the host's fault —
     * and a correction telling it to omit the directory it already omitted would be no remedy.
     */
    @Test
    public void shouldAnswerInternalError_whenTheDefaultTempDirectoryIsNotWritable() throws IOException {
        Path tempBase = Files.createDirectory(root.resolve("base"));
        Path exportDir = Files.createDirectory(tempBase.resolve("archi-mcp-export"));
        exportDir.toFile().setWritable(false);
        assumeFalse("a root user can write a read-only directory, so it cannot be made unwritable here",
                exportDir.toFile().canWrite());

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(null, tempBase));

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertEquals("Output directory is not writable: " + exportDir, e.getMessage());
        assertNull("no caller remedy exists for the host's temp directory", e.getSuggestedCorrection());
    }

    @Test
    public void shouldUseTheExportFolderUnderJavaIoTmpdir_whenNoDirectoryIsGiven() {
        Path prepared = ExportDirectory.prepare(null);

        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "archi-mcp-export"), prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    /** A single-segment relative name has no parent of its own; its ancestors are the working directory's. */
    @Test
    public void shouldWalkFromTheAbsolutePath_whenOutputDirectoryIsASingleRelativeSegment() {
        Path ancestor = ExportDirectory.nearestExisting(Path.of("out"));

        assertEquals(Path.of("").toAbsolutePath(), ancestor);
    }

    @Test
    public void shouldRejectAsNotADirectory_whenOutputDirectoryIsADanglingSymlink() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(link.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + link, e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsNotADirectory_whenAnAncestorIsADanglingSymlink() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"));
        Path dir = link.resolve("sub").resolve("dir");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + dir + " (" + link + " is not a directory)",
                e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsNotWritable_whenAnAncestorCannotBeSearched() throws IOException {
        Path ns = revokeSearch(Files.createDirectory(root.resolve("ns")));
        Path dir = ns.resolve("a").resolve("b");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not writable: " + dir + " (parent " + ns + " is not writable)",
                e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsNotWritableBeforeRendering_whenAnExistingDirectoryCannotBeSearched()
            throws IOException {
        Path d = revokeSearch(Files.createDirectory(root.resolve("unsearchable")));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(d.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not writable: " + d, e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsNotWritableWhenPrepared_whenAnExistingDirectoryCannotBeSearched()
            throws IOException {
        Path d = revokeSearch(Files.createDirectory(root.resolve("unsearchable")));

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(d.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not writable: " + d, e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldAnswerInternalError_whenTheDefaultTempDirectoryCannotBeSearched() throws IOException {
        Path tempBase = Files.createDirectory(root.resolve("base"));
        Path exportDir = revokeSearch(Files.createDirectory(tempBase.resolve("archi-mcp-export")));

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(null, tempBase));

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertEquals("Output directory is not writable: " + exportDir, e.getMessage());
        assertNull("no caller remedy exists for the host's temp directory", e.getSuggestedCorrection());
    }

    @Test
    public void shouldAcceptAndCreate_whenOutputDirectoryIsASymlinkToAWritableDirectory() throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);

        ExportDirectory.validate(link.toString());
        Path prepared = ExportDirectory.prepare(link.toString(), root);

        assertEquals(link, prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    @Test
    public void shouldAcceptAndCreate_whenAnAncestorIsASymlinkToAWritableDirectory() throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);
        Path dir = link.resolve("new").resolve("nested");

        ExportDirectory.validate(dir.toString());
        Path prepared = ExportDirectory.prepare(dir.toString(), root);

        assertEquals(dir, prepared);
        assertTrue(Files.isDirectory(real.resolve("new").resolve("nested")));
    }

    @Test
    public void shouldWriteTheFileAndReturnItsAbsolutePath_whenTheDirectoryIsUsable() throws IOException {
        Path written = ExportDirectory.write(root.toString(), root, "v.png", new byte[] {1, 2, 3});

        assertEquals(root.resolve("v.png").toAbsolutePath(), written);
        assertEquals(3, Files.size(written));
    }

    /** Reached past validation: the write is the net for a denial no check foresaw (a Windows ACL). */
    @Test
    public void shouldAnswerInvalidParameter_whenWritingIntoACallerDirectoryIsDenied() throws IOException {
        Path d = revokeSearch(Files.createDirectory(root.resolve("denied")));

        ModelAccessException e = expectRejection(
                () -> ExportDirectory.write(d.toString(), d, "v.png", new byte[] {1}));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Failed to write export file: " + d.resolve("v.png") + " (Permission denied)",
                e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
        assertTrue("the access denial is kept as the cause, got " + e.getCause(),
                e.getCause() instanceof AccessDeniedException);
    }

    @Test
    public void shouldAnswerInternalError_whenWritingIntoTheDefaultTempDirectoryIsDenied() throws IOException {
        Path d = revokeSearch(Files.createDirectory(root.resolve("denied")));

        for (String none : new String[] {null, "  "}) {
            ModelAccessException e = expectRejection(
                    () -> ExportDirectory.write(none, d, "v.png", new byte[] {1}));

            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertEquals("Failed to write export file: " + d.resolve("v.png") + " (Permission denied)",
                    e.getMessage());
            assertNull("no caller remedy exists for the host's temp directory", e.getSuggestedCorrection());
            assertTrue("the access denial is kept as the cause, got " + e.getCause(),
                    e.getCause() instanceof AccessDeniedException);
        }
    }

    /** Only a denial moves: a write failing for any other reason (disk full, I/O) is not the caller's. */
    @Test
    public void shouldAnswerInternalError_whenWritingIntoACallerDirectoryFailsForAnotherReason()
            throws IOException {
        Files.createDirectory(root.resolve("v.png"));

        ModelAccessException e = expectRejection(
                () -> ExportDirectory.write(root.toString(), root, "v.png", new byte[] {1}));

        assertTrue("precondition: the JDK reports a non-access failure here, got " + e.getCause(),
                e.getCause() instanceof FileSystemException);
        assertFalse("precondition: the JDK reports a non-access failure here, got " + e.getCause(),
                e.getCause() instanceof AccessDeniedException);
        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith("Failed to write export file: "));
        assertNull("a write failure that is not a denial has no caller remedy", e.getSuggestedCorrection());
    }

    /**
     * The system resolves {@code m/..} through {@code m}, so a path that steps back out of a
     * directory that does not exist cannot be followed, whatever the names around it.
     */
    @Test
    public void shouldRejectAsNotAValidPath_whenDotDotFollowsADirectoryThatDoesNotExist() {
        Path dir = root.resolve("m").resolve("..").resolve("new");
        assumeDotDotIsResolvedThroughTheName(root.resolve("m"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + root.resolve("m")
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
    }

    @Test
    public void shouldRejectAsNotAValidPath_whenDotDotFollowsAMissingDirectoryUnderASymlink()
            throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);
        Path dir = link.resolve("m").resolve("..").resolve("..").resolve("y");
        assumeDotDotIsResolvedThroughTheName(link.resolve("m"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + link.resolve("m")
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
    }

    @Test
    public void shouldRejectAsNotAValidPath_whenDotDotFromAMissingDirectoryNamesAnExistingOne()
            throws IOException {
        Files.createDirectory(root.resolve("real"));
        Path dir = root.resolve("m").resolve("..").resolve("real");
        assumeDotDotIsResolvedThroughTheName(root.resolve("m"));
        assertFalse("precondition: the system cannot resolve the path, so it does not exist",
                Files.exists(dir, LinkOption.NOFOLLOW_LINKS));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + root.resolve("m")
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
    }

    /** Creating the directory would make the normalized path, one the caller never named. */
    @Test
    public void shouldCreateNothing_whenDotDotFollowsADirectoryThatDoesNotExist() {
        Path dir = root.resolve("m").resolve("..").resolve("ok");
        assumeDotDotIsResolvedThroughTheName(root.resolve("m"));

        expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertFalse("nothing is created for a refused path", Files.exists(root.resolve("ok")));
    }

    /** A {@code ..} out of a directory that exists is followed by the system, through a link's target. */
    @Test
    public void shouldAcceptAndCreateThePhysicalDirectory_whenDotDotFollowsAnExistingSymlink()
            throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path sub = Files.createDirectory(real.resolve("sub"));
        Path link = Files.createSymbolicLink(root.resolve("link"), sub);
        Path dir = link.resolve("..").resolve("new");

        ExportDirectory.validate(dir.toString());
        Path prepared = ExportDirectory.prepare(dir.toString(), root);

        assertEquals(dir, prepared);
        assertTrue(Files.isDirectory(real.resolve("new")));
        assertFalse("the link's own parent is not where the system resolves it",
                Files.exists(root.resolve("new")));
    }

    @Test
    public void shouldAcceptAndCreate_whenDotDotFollowsAnExistingDirectory() throws IOException {
        Files.createDirectory(root.resolve("real"));
        Path dir = root.resolve("real").resolve("..").resolve("new2");

        ExportDirectory.validate(dir.toString());
        Path prepared = ExportDirectory.prepare(dir.toString(), root);

        assertEquals(dir, prepared);
        assertTrue(Files.isDirectory(root.resolve("new2")));
    }

    @Test
    public void shouldAccept_whenTheDirectoryIsAnExistingOneFollowedByDotDot() throws IOException {
        Files.createDirectory(root.resolve("real"));
        Path dir = root.resolve("real").resolve("..");

        ExportDirectory.validate(dir.toString());
        Path prepared = ExportDirectory.prepare(dir.toString(), root);

        assertEquals(dir, prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    /** The ancestor's own problem is the one to fix first, so its message wins over the {@code ..}. */
    @Test
    public void shouldRejectAsNotADirectory_whenAnAncestorIsAFileAndDotDotFollowsAMissingName()
            throws IOException {
        Path file = Files.createTempFile(root, "blocker", ".txt");
        Path dir = file.resolve("m").resolve("..").resolve("x");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + dir + " (" + file + " is not a directory)",
                e.getMessage());
    }

    @Test
    public void shouldNameTheFirstMissingDirectory_whenSeveralPrecedeTheDotDot() {
        Path dir = root.resolve("a").resolve("b").resolve("..").resolve("x");
        assumeDotDotIsResolvedThroughTheName(root.resolve("a"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + root.resolve("a")
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
    }

    @Test
    public void shouldRejectAsNotAValidPath_whenThePathEndsInDotDotAfterAMissingDirectory() {
        Path dir = root.resolve("m").resolve("..");
        assumeDotDotIsResolvedThroughTheName(root.resolve("m"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals("Output directory is not a valid path: " + dir + " (" + root.resolve("m")
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
    }

    /** A relative name is printed as given; the missing directory is named where it resolves. */
    @Test
    public void shouldRejectAsNotAValidPath_whenARelativePathStepsBackOutOfAMissingDirectory() {
        Path cwd = Path.of("").toAbsolutePath();
        assumeTrue("the working directory must accept new directories for the walk to reach the '..'",
                cwd.toFile().canWrite() && cwd.toFile().canExecute());
        String name = "export-directory-test-missing-" + System.nanoTime();
        Path dir = Path.of(name, "..", "x");
        assumeDotDotIsResolvedThroughTheName(cwd.resolve(name));

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + cwd.resolve(name)
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
    }

    /** A broken link stops the walk and is reported as what it is, before any '..' after it. */
    @Test
    public void shouldRejectAsNotADirectory_whenDotDotFollowsADanglingSymlink() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"));
        Path dir = link.resolve("..").resolve("x");

        ModelAccessException e = expectRejection(() -> ExportDirectory.validate(dir.toString()));

        assertEquals("Output directory is not a directory: " + dir + " (" + link + " is not a directory)",
                e.getMessage());
    }

    /**
     * The directory the {@code ..} steps back out of can disappear while the view renders, between
     * the validation and the preparation; creating then would make the normalized path.
     */
    @Test
    public void shouldRejectAsNotAValidPathAndCreateNothing_whenTheDirectoryBeforeDotDotIsRemovedAfterValidation()
            throws IOException {
        Path m = Files.createDirectory(root.resolve("m"));
        Path dir = m.resolve("..").resolve("new");
        ExportDirectory.validate(dir.toString());
        Files.delete(m);
        assumeDotDotIsResolvedThroughTheName(m);

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(dir.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + m
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
        assertFalse("nothing is created for a refused path", Files.exists(root.resolve("new")));
    }

    @Test
    public void shouldNameTheRemovedDirectoryAndCreateNothing_whenANestedDirectoryBeforeDotDotIsRemovedAfterValidation()
            throws IOException {
        Path a = Files.createDirectory(root.resolve("a"));
        Path m = Files.createDirectory(a.resolve("m"));
        Path dir = m.resolve("..").resolve("..").resolve("new");
        ExportDirectory.validate(dir.toString());
        Files.delete(m);
        assumeDotDotIsResolvedThroughTheName(m);

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(dir.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a valid path: " + dir + " (" + m
                + " does not exist, so '..' after it cannot be followed)", e.getMessage());
        assertFalse("nothing is created for a refused path", Files.exists(root.resolve("new")));
        assertTrue("the directory that still exists is left as it was", Files.isDirectory(a));
    }

    /**
     * A broken link left where the directory before the {@code ..} was is answered as validation
     * answers it, before creating would make the normalized path.
     */
    @Test
    public void shouldRejectAsNotADirectoryAndCreateNothing_whenTheDirectoryBeforeDotDotIsReplacedByADanglingSymlink()
            throws IOException {
        Path m = Files.createDirectory(root.resolve("m"));
        Path dir = m.resolve("..").resolve("new");
        ExportDirectory.validate(dir.toString());
        Files.delete(m);
        Files.createSymbolicLink(m, root.resolve("nowhere"));

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(dir.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals("Output directory is not a directory: " + dir + " (" + m + " is not a directory)",
                e.getMessage());
        assertEquals(ExportDirectory.CORRECTION, e.getSuggestedCorrection());
        assertFalse("nothing is created for a refused path", Files.exists(root.resolve("new")));
    }

    /** Where the ancestor is a file, creating it fails first and keeps the file-system cause. */
    @Test
    public void shouldKeepTheCreationFailureAndItsCause_whenAnAncestorIsAFileAndDotDotFollowsAMissingName()
            throws IOException {
        Path file = Files.createTempFile(root, "blocker", ".txt");
        Path dir = file.resolve("m").resolve("..").resolve("x");

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(dir.toString(), root));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith("Failed to create output directory: "));
        assertTrue("the file-system failure is kept as the cause, got " + e.getCause(),
                e.getCause() instanceof IOException);
    }

    /** The caller named no directory, so a temp base the system cannot follow is the host's fault. */
    @Test
    public void shouldAnswerInternalError_whenTheTempBaseStepsBackOutOfADirectoryThatDoesNotExist() {
        Path missing = root.resolve("m");
        assumeDotDotIsResolvedThroughTheName(missing);

        ModelAccessException e = expectRejection(() -> ExportDirectory.prepare(null, missing.resolve("..")));

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
    }

    /**
     * Takes away search (and read) permission, leaving write: files cannot be created in the directory.
     * A root user keeps it regardless, so the test skips rather than passes.
     */
    private Path revokeSearch(Path dir) {
        searchRevoked.add(dir);
        dir.toFile().setReadable(false);
        dir.toFile().setExecutable(false);
        assumeFalse("a root user can search any directory, so it cannot be made unsearchable here",
                dir.toFile().canExecute());
        return dir;
    }

    /**
     * A system that collapses {@code ..} before resolving (Windows) can follow one out of a missing
     * directory, so the refusal does not apply there and the test skips rather than passes.
     */
    private static void assumeDotDotIsResolvedThroughTheName(Path missing) {
        assumeFalse("this system collapses '..' before resolving, so it can step back out of a missing name",
                Files.exists(missing.resolve(".."), LinkOption.NOFOLLOW_LINKS));
    }

    private static ModelAccessException expectRejection(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            assertNotNull(e.getMessage());
            return e;
        }
        fail("expected the directory to be rejected");
        return null;
    }
}
