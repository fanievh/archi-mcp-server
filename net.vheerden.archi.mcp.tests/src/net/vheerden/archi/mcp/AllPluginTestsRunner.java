package net.vheerden.archi.mcp;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Batch runner for all JUnit Plug-in Tests.
 * Runs each test class via JUnitCore inside a PDE environment
 * and writes results to /tmp/plugin_test_results.txt.
 *
 * Temporary utility for Story 12-1 — can be deleted after release.
 */
public class AllPluginTestsRunner {

    private static final Class<?>[] TEST_CLASSES;

    static {
        try {
            TEST_CLASSES = new Class<?>[] {
                Class.forName("net.vheerden.archi.mcp.handlers.ApprovalHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.CommandStackHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteElementTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteFolderTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteRelationshipTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteViewTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.DiscoveryHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ElementCreationHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ElementUpdateHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.FolderHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.FolderMutationHandlerCreateTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.FolderMutationHandlerMoveTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.FolderMutationHandlerUpdateTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ModelQueryHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.MutationHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.RenderHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ResourceHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.SearchHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.SessionHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.TraversalHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ViewHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ViewHandlerTreeFormatTest"),
                Class.forName("net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest"),
                Class.forName("net.vheerden.archi.mcp.integration.ErrorConsistencyTest"),
                Class.forName("net.vheerden.archi.mcp.integration.MultiStepWorkflowTest"),
                Class.forName("net.vheerden.archi.mcp.integration.ToolDiscoveryIntegrationTest"),
                Class.forName("net.vheerden.archi.mcp.model.AddConnectionToViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.AddToViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.ArchiModelAccessorImplTest"),
                Class.forName("net.vheerden.archi.mcp.model.ClearViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.CreateElementCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.CreateRelationshipCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.CreateViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.ElkLayoutEngineTest"),
                Class.forName("net.vheerden.archi.mcp.model.LayoutEngineTest"),
                Class.forName("net.vheerden.archi.mcp.model.MutationContextTest"),
                Class.forName("net.vheerden.archi.mcp.model.MutationDispatcherTest"),
                Class.forName("net.vheerden.archi.mcp.model.RemoveConnectionFromViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.RemoveFromViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.UpdateElementCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.UpdateViewCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.UpdateViewConnectionCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.UpdateViewObjectCommandTest"),
                Class.forName("net.vheerden.archi.mcp.model.routing.RoutingComparisonTest"),
                Class.forName("net.vheerden.archi.mcp.registry.CommandRegistryTest"),
                Class.forName("net.vheerden.archi.mcp.registry.ResourceRegistryTest"),
                Class.forName("net.vheerden.archi.mcp.server.TransportConfigTest"),
                Class.forName("net.vheerden.archi.mcp.logging.EclipseLoggerTest"),
                Class.forName("net.vheerden.archi.mcp.ui.IpAddressFieldEditorTest"),
                Class.forName("net.vheerden.archi.mcp.ui.McpPreferencePageTest"),
                // Known failures - included for documentation
                Class.forName("net.vheerden.archi.mcp.server.McpServerManagerTest"),
                Class.forName("net.vheerden.archi.mcp.ui.McpPreferenceInitializerTest"),
            };
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Test class not found: " + e.getMessage(), e);
        }
    }

    @Test
    public void runAllPluginTests() throws Exception {
        String outputPath = System.getProperty("java.io.tmpdir") + File.separator + "plugin_test_results.txt";
        int passCount = 0;
        int failCount = 0;
        int totalTests = 0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            for (Class<?> testClass : TEST_CLASSES) {
                String name = testClass.getSimpleName();
                try {
                    Result result = JUnitCore.runClasses(testClass);
                    totalTests += result.getRunCount();
                    if (result.wasSuccessful()) {
                        pw.println("PASS  " + name + " (" + result.getRunCount() + " tests)");
                        passCount++;
                    } else {
                        pw.println("FAIL  " + name + " (run=" + result.getRunCount()
                                + " fail=" + result.getFailureCount() + ")");
                        for (Failure f : result.getFailures()) {
                            pw.println("  -> " + f.getTestHeader() + ": " + f.getMessage());
                        }
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
            pw.println("SUMMARY: " + passCount + " passed, " + failCount + " failed out of "
                    + TEST_CLASSES.length + " test classes (" + totalTests + " individual tests)");
        }

        // Also print to stdout for visibility
        System.out.println("Plugin test results written to " + outputPath);
        System.out.println("SUMMARY: " + passCount + " passed, " + failCount + " failed");
    }
}
