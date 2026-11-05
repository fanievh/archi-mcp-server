package net.vheerden.archi.mcp;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Eclipse PDE batch runner — the full in-OSGi gate that complements the headless harness.
 *
 * <p><strong>This list is NOT the source of truth for "the whole suite."</strong> The canonical,
 * auto-discovered run is the headless harness {@code tools/run-tests.sh}, which scans
 * {@code net.vheerden.archi.mcp.tests/src} for every {@code *Test.java}, subtracts the exclusion
 * manifest {@code tools/osgi-excluded-tests.txt}, compiles both projects from source, runs the
 * headless-safe majority via {@code JUnitCore}, asserts {@code testsRun > 0} per class, and emits
 * JUnit XML. That harness closes the gap this runner leaves open: a hand-maintained list can
 * silently omit a class ({@code AllPluginTestsRunnerTest} pins the manifest's Group A
 * here, headlessly). The single
 * enumeration of HEADLESS-EXCLUDED classes lives in that manifest.</p>
 *
 * <p>This runner is the owner's convenience gate for exercising classes under a real Eclipse/OSGi
 * runtime (the only place the {@code tools/osgi-excluded-tests.txt} classes — {@code McpServerManagerTest},
 * {@code McpPreferenceInitializerTest}, the SWT/display-required classes, etc. — actually run rather
 * than assumption-skip), and for the single tests in headless classes that skip themselves off-OSGi
 * ({@code AllPluginTestsRunnerTest} fails if a class using one of the skip idioms it names is not
 * listed here). Results are written to {@code plugin_test_results.txt} in
 * {@code java.io.tmpdir} (on macOS a per-user {@code /var/folders/.../T/}, not {@code /tmp}). A class
 * that ran no tests, or in which ANY test was assumption-skipped, is reported as a FAIL: under this
 * runtime a skip means the class fell back to its off-OSGi path, which is exactly what this runner
 * exists to rule out.</p>
 *
 * <p><strong>Launch arguments.</strong> The secure-storage round trips ({@code BearerTokenStoreTest},
 * {@code KeystorePasswordStoreTest}) run only when the launch names its own throwaway store; without it
 * they are assumption-skipped, so those two classes FAIL here. Program arguments:
 * {@code -eclipse.keyring ${workspace_loc}/../junit-workspace/secure_storage} (cleared with the workspace
 * before each run) and {@code -eclipse.password <file holding a dummy password>}. Each skip's message is
 * printed under its class's FAIL line.</p>
 *
 * <p><strong>Staleness is now non-fatal.</strong> Class names are resolved per-class at run time, so
 * a deleted/renamed test no longer throws {@code ExceptionInInitializerError} and kills the whole
 * gate — it is reported as a loud {@code LOAD-ERROR} line (and counts as a failure) while the rest
 * still run. (Before this change one stale entry — {@code LayoutEngineTest} — took down the entire
 * launch.) The headless harness remains the staleness-proof gate; this is the in-OSGi complement.</p>
 */
public class AllPluginTestsRunner {

    // Class NAMES (not Class objects) so a missing entry cannot break static init — it is resolved
    // and reported per-class in runAllPluginTests(). Keep alphabetised; the headless-excluded
    // classes (tools/osgi-excluded-tests.txt) are grouped at the end — those are the ones this
    // in-OSGi gate uniquely exercises (they assumption-skip headlessly).
    static final String[] TEST_CLASS_NAMES = {
        "net.vheerden.archi.mcp.handlers.ApprovalHandlerTest",
        "net.vheerden.archi.mcp.handlers.CommandStackHandlerTest",
        "net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteElementTest",
        "net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteFolderTest",
        "net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteRelationshipTest",
        "net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteViewTest",
        "net.vheerden.archi.mcp.handlers.DiscoveryHandlerTest",
        "net.vheerden.archi.mcp.handlers.ElementCreationHandlerTest",
        "net.vheerden.archi.mcp.handlers.ElementUpdateHandlerTest",
        "net.vheerden.archi.mcp.handlers.FolderHandlerTest",
        "net.vheerden.archi.mcp.handlers.FolderMutationHandlerCreateTest",
        "net.vheerden.archi.mcp.handlers.FolderMutationHandlerMoveTest",
        "net.vheerden.archi.mcp.handlers.FolderMutationHandlerUpdateTest",
        "net.vheerden.archi.mcp.handlers.ModelQueryHandlerTest",
        "net.vheerden.archi.mcp.handlers.MutationHandlerTest",
        "net.vheerden.archi.mcp.handlers.RenderHandlerTest",
        "net.vheerden.archi.mcp.handlers.ResourceHandlerTest",
        "net.vheerden.archi.mcp.handlers.SearchHandlerTest",
        "net.vheerden.archi.mcp.handlers.SessionHandlerTest",
        "net.vheerden.archi.mcp.handlers.TraversalHandlerTest",
        "net.vheerden.archi.mcp.handlers.ViewHandlerTest",
        "net.vheerden.archi.mcp.handlers.ViewHandlerTreeFormatTest",
        "net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest",
        "net.vheerden.archi.mcp.integration.DeleteViewSurvivingPlaceholderConnectionRoundTripIntegrationTest",
        "net.vheerden.archi.mcp.integration.ErrorConsistencyTest",
        "net.vheerden.archi.mcp.integration.MultiStepWorkflowTest",
        "net.vheerden.archi.mcp.integration.ToolDiscoveryIntegrationTest",
        "net.vheerden.archi.mcp.model.AddConnectionToViewCommandTest",
        "net.vheerden.archi.mcp.model.AddToViewCommandTest",
        "net.vheerden.archi.mcp.model.ArchiModelAccessorImplTest",
        "net.vheerden.archi.mcp.model.BatchQueuedParentContainerTest",
        "net.vheerden.archi.mcp.model.BulkInsideOpenBatchErrorTest",
        "net.vheerden.archi.mcp.model.ClearViewCommandTest",
        "net.vheerden.archi.mcp.model.CreateElementCommandTest",
        "net.vheerden.archi.mcp.model.CreateRelationshipCommandTest",
        "net.vheerden.archi.mcp.model.CreateViewCommandTest",
        "net.vheerden.archi.mcp.model.DeferredPlacementDeclineTest",
        "net.vheerden.archi.mcp.model.DeleteViewCommandCompoundCascadeTest",
        "net.vheerden.archi.mcp.model.ElkLayoutEngineTest",
        "net.vheerden.archi.mcp.model.MultiViewCompoundDeleteCascadeIntegrationTest",
        "net.vheerden.archi.mcp.model.MultiViewDeleteCascadeTest",
        "net.vheerden.archi.mcp.model.MutationContextTest",
        "net.vheerden.archi.mcp.model.MutationDispatcherTest",
        "net.vheerden.archi.mcp.model.RelationshipSemanticAttributesIntegrationTest",
        "net.vheerden.archi.mcp.model.RemoveConnectionFromViewCommandTest",
        "net.vheerden.archi.mcp.model.RemoveFromViewCommandTest",
        "net.vheerden.archi.mcp.model.UpdateElementCommandTest",
        "net.vheerden.archi.mcp.model.UpdateViewCommandTest",
        "net.vheerden.archi.mcp.model.UpdateViewConnectionCommandTest",
        "net.vheerden.archi.mcp.model.UpdateViewObjectCommandTest",
        "net.vheerden.archi.mcp.model.routing.RoutingComparisonTest",
        "net.vheerden.archi.mcp.registry.CommandRegistryTest",
        "net.vheerden.archi.mcp.registry.ResourceRegistryTest",
        "net.vheerden.archi.mcp.server.BearerTokenStoreTest",
        "net.vheerden.archi.mcp.server.KeystorePasswordStoreTest",
        "net.vheerden.archi.mcp.server.TransportConfigTest",
        "net.vheerden.archi.mcp.logging.EclipseLoggerTest",
        "net.vheerden.archi.mcp.ui.IpAddressFieldEditorTest",
        "net.vheerden.archi.mcp.ui.McpPreferencePageTest",
        // Headless-excluded classes (tools/osgi-excluded-tests.txt) — exercised here under OSGi.
        "net.vheerden.archi.mcp.integration.DeleteViewCascadeIntegrationTest",
        "net.vheerden.archi.mcp.integration.DeleteViewNestedConnectionRoundTripIntegrationTest",
        "net.vheerden.archi.mcp.integration.RemoveFromViewCascadeIntegrationTest",
        "net.vheerden.archi.mcp.model.ArchiNoticeBracketTest",
        "net.vheerden.archi.mcp.model.ImagePathArchiveValidationTest",
        "net.vheerden.archi.mcp.model.ViewExportServiceTest",
        "net.vheerden.archi.mcp.server.McpServerManagerTest",
        "net.vheerden.archi.mcp.ui.McpPreferenceInitializerTest",
    };

    @Test
    public void runAllPluginTests() throws Exception {
        String outputPath = System.getProperty("java.io.tmpdir") + File.separator + "plugin_test_results.txt";
        int passCount = 0;
        int failCount = 0;
        int loadErrors = 0;
        int totalTests = 0;
        int totalSkipped = 0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            for (String className : TEST_CLASS_NAMES) {
                Class<?> testClass;
                try {
                    testClass = Class.forName(className);
                } catch (Throwable t) {
                    // Resilient: a deleted/renamed test is reported loudly, not fatal to the gate.
                    pw.println("LOAD-ERROR " + className + ": " + t.getClass().getSimpleName()
                            + " - " + t.getMessage() + "  (remove or fix this entry)");
                    pw.flush();
                    loadErrors++;
                    failCount++;
                    continue;
                }
                String name = testClass.getSimpleName();
                try {
                    List<Failure> skips = new ArrayList<>();
                    Result result = runClass(testClass, skips);
                    totalTests += result.getRunCount();
                    int skipped = result.getAssumptionFailureCount();
                    totalSkipped += skipped;
                    String counts = result.getRunCount() + " tests, " + skipped + " skipped, "
                            + result.getIgnoreCount() + " ignored";
                    // JUnit 4 counts an assumption-failed test as run and successful, so a class that
                    // silently fell back to skipping (e.g. no OSGi) would otherwise read as a pass.
                    String notRun = notRunReason(result.getRunCount(), skipped, result.getIgnoreCount());
                    if (result.wasSuccessful() && notRun != null) {
                        pw.println("FAIL  " + name + " (" + counts + ") - " + notRun);
                        printSkips(pw, skips);
                        failCount++;
                    } else if (result.wasSuccessful()) {
                        pw.println("PASS  " + name + " (" + counts + ")");
                        passCount++;
                    } else {
                        pw.println("FAIL  " + name + " (" + counts + ", fail=" + result.getFailureCount() + ")");
                        for (Failure f : result.getFailures()) {
                            pw.println("  -> " + f.getTestHeader() + ": " + f.getMessage());
                        }
                        printSkips(pw, skips);
                        failCount++;
                    }
                } catch (Exception e) {
                    pw.println("ERROR " + name + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    failCount++;
                }
                pw.flush();
            }

            pw.println();
            pw.println("========================================");
            pw.println("SUMMARY: " + passCount + " passed, " + failCount + " failed (of which "
                    + loadErrors + " load-errors) out of " + TEST_CLASS_NAMES.length
                    + " test classes (" + totalTests + " individual tests, " + totalSkipped + " skipped)");
        }

        // Also print to stdout for visibility
        System.out.println("Plugin test results written to " + outputPath);
        System.out.println("SUMMARY: " + passCount + " passed, " + failCount + " failed ("
                + loadErrors + " load-errors)");
    }

    /**
     * Runs one class, collecting each assumption skip: {@link Result} counts them but drops their
     * messages, and a skip's message is what says how to make the test run here.
     */
    static Result runClass(Class<?> testClass, List<Failure> skips) {
        JUnitCore core = new JUnitCore();
        core.addListener(new RunListener() {
            @Override
            public void testAssumptionFailure(Failure failure) {
                skips.add(failure);
            }
        });
        return core.run(testClass);
    }

    private static void printSkips(PrintWriter pw, List<Failure> skips) {
        for (Failure f : skips) {
            pw.println("  skip -> " + f.getTestHeader() + ": " + f.getMessage());
        }
    }

    /**
     * Why a successful class still did not really run, or {@code null} when it did. Under the full
     * PDE runtime nothing should assumption-skip, so ANY skip fails the class: one real test must not
     * hide siblings that fell back to their off-OSGi skip. {@code skipped} counts a class-level
     * ({@code @BeforeClass}) assumption once, with zero tests run.
     */
    static String notRunReason(int run, int skipped, int ignored) {
        if (run == 0 && skipped > 0) {
            return "class-level assumption skip, no test ran";
        }
        if (run == 0) {
            return ignored > 0 ? "every test is @Ignore'd" : "no test ran";
        }
        if (skipped == run) {
            return "every test was assumption-skipped";
        }
        if (skipped > 0) {
            return skipped + " of " + run + " tests assumption-skipped";
        }
        return null;
    }
}
