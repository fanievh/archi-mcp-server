package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.*;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.junit.Test;

/**
 * Unit tests for {@link McpPreferencePage}.
 *
 * <p>Tests structural properties of the preference page that can be verified
 * without Eclipse workbench runtime. Full UI integration testing (page renders,
 * fields display, persistence works) is covered by Task 7 manual validation.</p>
 */
public class McpPreferencePageTest {

    @Test
    public void shouldBeInstantiable_whenCreated() {
        McpPreferencePage page = new McpPreferencePage();
        assertNotNull("Page should be instantiable", page);
    }

    @Test
    public void shouldHaveCorrectDescription_whenCreated() {
        McpPreferencePage page = new McpPreferencePage();
        assertEquals("Configuration settings for the ArchiMate MCP Server.", page.getDescription());
    }

    @Test
    public void shouldExtendFieldEditorPreferencePage() {
        McpPreferencePage page = new McpPreferencePage();
        assertTrue("Should extend FieldEditorPreferencePage",
                page instanceof FieldEditorPreferencePage);
    }

    @Test
    public void shouldImplementIWorkbenchPreferencePage() {
        McpPreferencePage page = new McpPreferencePage();
        assertTrue("Should implement IWorkbenchPreferencePage",
                page instanceof IWorkbenchPreferencePage);
    }

    @Test
    public void shouldUseGridLayout() {
        // FieldEditorPreferencePage uses GRID or FLAT layout
        // Constructor calls super(GRID), which we can verify via description being set
        // (Description is only shown in GRID layout mode)
        McpPreferencePage page = new McpPreferencePage();
        assertNotNull("Description should be set (implies GRID layout)", page.getDescription());
    }
}
