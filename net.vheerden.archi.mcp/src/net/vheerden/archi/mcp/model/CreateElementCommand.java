package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that adds an ArchiMate element to a folder (Story 7-2).
 *
 * <p>The element must be fully configured (name, documentation, properties)
 * before this command is created. The command only handles folder placement
 * and removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class CreateElementCommand extends Command {

    private final IArchimateElement element;
    private final IFolder folder;

    /**
     * Creates a command to add an element to a folder.
     *
     * @param element the fully-configured element to add
     * @param folder  the target folder
     */
    public CreateElementCommand(IArchimateElement element, IFolder folder) {
        this.element = element;
        this.folder = folder;
        setLabel("Create " + element.eClass().getName() + ": " + element.getName());
    }

    @Override
    public void execute() {
        folder.getElements().add(element);
    }

    @Override
    public void undo() {
        folder.getElements().remove(element);
    }

    /**
     * Returns the element this command will add.
     * Package-visible for testing.
     */
    IArchimateElement getElement() {
        return element;
    }

    /**
     * Returns the target folder.
     * Package-visible for testing.
     */
    IFolder getFolder() {
        return folder;
    }
}
