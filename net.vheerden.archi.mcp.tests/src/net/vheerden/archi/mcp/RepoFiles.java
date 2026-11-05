package net.vheerden.archi.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads files from the repository checkout, for tests that check what the docs or the source say.
 * The root is found by walking up from the working directory until {@code README.md} and the plugin
 * project sit side by side, so a test reads the same files from the plugin, the tests project or the
 * repo root.
 */
public final class RepoFiles {

    private static final String PLUGIN = "net.vheerden.archi.mcp";

    private RepoFiles() {
    }

    /** The repository root. */
    public static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve("README.md")) && Files.isDirectory(dir.resolve(PLUGIN))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate the repository root from " + Path.of("").toAbsolutePath());
    }

    /** A file's text, by its repo-relative path. */
    public static String read(String relative) {
        try {
            return Files.readString(root().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file under a repo-relative directory whose name ends with {@code suffix}, sorted, as repo-relative paths. */
    public static List<String> under(String relativeDir, String suffix) {
        Path root = root();
        Path dir = root.resolve(relativeDir);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
