package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Holds the layer boundary the architecture document states: only the {@code model} package
 * reaches EMF or ArchimateTool types. Handlers, the protocol layer, the UI and every supporting
 * package go through the {@link ArchiModelAccessor} interface and see DTOs.
 *
 * <p>The rule is checked on code, not on prose: comments and string literals are removed before
 * the scan, so a Javadoc that names {@code com.archimatetool.editor} or a bundle id held in a
 * string is not a dependency. What is left is an {@code import} (plain or static) or a
 * fully-qualified type reference, and either one outside {@code model} fails the build, naming
 * the file and the reference.</p>
 *
 * <p><strong>The scan cannot pass by matching nothing.</strong> It requires a floor of files on
 * each side of the boundary, and it runs the same detector over the {@code model} package, where
 * it must find EMF or ArchimateTool references. A pattern broken so that it matches nothing turns
 * that positive control red rather than passing the boundary check over an empty set.</p>
 */
public class ModelLayerBoundaryContractTest {

    private static final String[] SOURCE_ROOTS = {
            "net.vheerden.archi.mcp/src",
            "../net.vheerden.archi.mcp/src",
    };

    /** The package below the source root that is allowed to touch the model framework. */
    private static final String MODEL_PACKAGE = "net/vheerden/archi/mcp/model/";

    /** A reference to an EMF or ArchimateTool type, as code, once comments and strings are gone. */
    private static final Pattern MODEL_FRAMEWORK_REFERENCE =
            Pattern.compile("\\b(?:org\\.eclipse\\.emf|com\\.archimatetool)\\.(?:[A-Za-z_][\\w.]*|\\*)");

    /**
     * Lower bounds on how many files each side holds. Far below today's counts, so they move only
     * when a root stops resolving or a walk stops descending, which is what they are here to catch.
     */
    private static final int MIN_FILES_OUTSIDE_MODEL = 50;
    private static final int MIN_MODEL_FILES_WITH_REFERENCES = 50;

    @Test
    public void shouldKeepEmfAndArchimateToolTypesInsideTheModelPackage_soHandlersReachThemOnlyThroughTheAccessor() {
        Path root = firstResolvable();
        List<Path> outside = javaFiles(root).stream()
                .filter(p -> !isModel(root, p))
                .collect(Collectors.toList());
        assertTrue("Only " + outside.size() + " source files found outside the model package under "
                + root.toAbsolutePath() + "; the scan is not reading the tree it claims to",
                outside.size() >= MIN_FILES_OUTSIDE_MODEL);

        List<String> breaches = new ArrayList<>();
        for (Path file : outside) {
            Matcher m = MODEL_FRAMEWORK_REFERENCE.matcher(codeOnly(read(file)));
            while (m.find()) {
                breaches.add(root.relativize(file) + ": " + m.group());
            }
        }
        assertTrue("These files outside the model package reference EMF or ArchimateTool types. "
                + "Add a method to ArchiModelAccessor and return a DTO instead:\n  "
                + String.join("\n  ", breaches), breaches.isEmpty());
    }

    @Test
    public void shouldFindModelFrameworkReferencesInsideTheModelPackage_soTheBoundaryScanCannotPassByMatchingNothing() {
        Path root = firstResolvable();
        long withReferences = javaFiles(root).stream()
                .filter(p -> isModel(root, p))
                .filter(p -> MODEL_FRAMEWORK_REFERENCE.matcher(codeOnly(read(p))).find())
                .count();
        assertTrue("The detector found EMF or ArchimateTool references in only " + withReferences
                + " model files; it should find at least " + MIN_MODEL_FILES_WITH_REFERENCES
                + ". If this is red, the boundary check above is passing over a detector that "
                + "cannot see the thing it forbids", withReferences >= MIN_MODEL_FILES_WITH_REFERENCES);
    }

    @Test
    public void shouldIgnoreCommentsAndStrings_butNotCode() {
        String source = "/** Uses {@code com.archimatetool.editor}. */\n"
                + "// org.eclipse.emf.ecore.EObject in a line comment\n"
                + "String bundle = \"com.archimatetool.export.svg\";\n"
                + "char quote = '\"'; String tail = \"org.eclipse.emf.x\";\n"
                + "import com.archimatetool.model.IArchimateModel;\n"
                + "org.eclipse.emf.ecore.EObject inline;\n"
                + "String block = \"\"\"\n  ends \\\"\"\" not here, com.archimatetool.x\n  \"\"\";\n"
                + "import com.archimatetool.*;\n";
        Matcher m = MODEL_FRAMEWORK_REFERENCE.matcher(codeOnly(source));
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        assertTrue("Expected exactly the import and the inline reference, got " + found,
                found.equals(List.of("com.archimatetool.model.IArchimateModel", "org.eclipse.emf.ecore.EObject",
                        "com.archimatetool.*")));
    }

    /** The source with comments, string literals, text blocks and char literals blanked out. */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i += 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isModel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/').startsWith(MODEL_PACKAGE);
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path firstResolvable() {
        for (String candidate : SOURCE_ROOTS) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new AssertionError("None of " + String.join(", ", SOURCE_ROOTS) + " resolved from "
                + Paths.get("").toAbsolutePath() + "; the scan cannot silently cover nothing");
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
