package net.vheerden.archi.mcp.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * A class that starts a child process sharing the runner's standard output, then never finishes in
 * time, for {@link HeadlessTestRunnerWatchdogTest}. Deliberately not named {@code *Test} so the
 * harness scan never runs it as a real class.
 *
 * <p>The child holds the write end of whatever the runner's output is connected to. When that is a
 * pipe, the reader sees end-of-file only once the child is gone too, not when the runner exits.</p>
 */
public class SpawnsAChildThenSleepsFixture {

    /** System property naming a file the child's pid is written to, so the caller can find it. */
    static final String CHILD_PID_FILE_PROPERTY = "harness.fixture.childPidFile";

    @Test
    public void spawnsAChildThenSleepsFarPastAnyDeadline() throws Exception {
        spawnInheritingChild();
        Thread.sleep(600_000L);
    }

    /** Starts a long-lived child that inherits this JVM's standard streams and records its pid. */
    static Process spawnInheritingChild() throws IOException {
        Process child = new ProcessBuilder("sleep", "600").inheritIO().start();
        String pidFile = System.getProperty(CHILD_PID_FILE_PROPERTY);
        if (pidFile != null && !pidFile.isBlank()) {
            Files.writeString(Path.of(pidFile), Long.toString(child.pid()), StandardCharsets.UTF_8);
        }
        return child;
    }
}
