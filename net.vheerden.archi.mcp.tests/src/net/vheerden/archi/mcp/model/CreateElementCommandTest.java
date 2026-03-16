package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link CreateElementCommand} (Story 7-2).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to folder) and undo (remove from folder) behavior.</p>
 */
public class CreateElementCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder businessFolder;
    private IBusinessActor element;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        businessFolder = model.getFolder(FolderType.BUSINESS);

        element = factory.createBusinessActor();
        element.setName("Test Actor");
    }

    @Test
    public void shouldAddElementToFolder_whenExecuted() {
        CreateElementCommand cmd = new CreateElementCommand(element, businessFolder);

        cmd.execute();

        assertTrue("Element should be in folder",
                businessFolder.getElements().contains(element));
    }

    @Test
    public void shouldRemoveElementFromFolder_whenUndone() {
        CreateElementCommand cmd = new CreateElementCommand(element, businessFolder);
        cmd.execute();

        cmd.undo();

        assertFalse("Element should not be in folder after undo",
                businessFolder.getElements().contains(element));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        CreateElementCommand cmd = new CreateElementCommand(element, businessFolder);

        String label = cmd.getLabel();

        assertTrue("Label should contain element type",
                label.contains("BusinessActor"));
        assertTrue("Label should contain element name",
                label.contains("Test Actor"));
    }

    @Test
    public void shouldNotAffectExistingElements_whenExecuted() {
        // Add an existing element first
        IBusinessActor existing = factory.createBusinessActor();
        existing.setName("Existing");
        businessFolder.getElements().add(existing);
        int initialCount = businessFolder.getElements().size();

        CreateElementCommand cmd = new CreateElementCommand(element, businessFolder);
        cmd.execute();

        assertEquals("Should have one more element", initialCount + 1,
                businessFolder.getElements().size());
        assertTrue("Existing element should still be present",
                businessFolder.getElements().contains(existing));
    }

    @Test
    public void shouldRestoreExactState_whenExecuteThenUndo() {
        IBusinessActor existing = factory.createBusinessActor();
        existing.setName("Existing");
        businessFolder.getElements().add(existing);
        int initialCount = businessFolder.getElements().size();

        CreateElementCommand cmd = new CreateElementCommand(element, businessFolder);
        cmd.execute();
        cmd.undo();

        assertEquals("Should be back to initial count", initialCount,
                businessFolder.getElements().size());
    }
}
