package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that adds an ArchiMate view (diagram) to a folder (Story 7-2).
 *
 * <p>The view must be fully configured (name, viewpoint) before this command
 * is created. The command only handles folder placement and removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class CreateViewCommand extends Command {

    private final IArchimateDiagramModel view;
    private final IFolder folder;

    /**
     * Creates a command to add a view to a folder.
     *
     * @param view   the fully-configured view to add
     * @param folder the target folder (typically the Views folder)
     */
    public CreateViewCommand(IArchimateDiagramModel view, IFolder folder) {
        this.view = view;
        this.folder = folder;
        setLabel("Create view: " + view.getName());
    }

    @Override
    public void execute() {
        folder.getElements().add(view);
    }

    @Override
    public void undo() {
        folder.getElements().remove(view);
    }

    /**
     * Returns the view this command will add.
     * Package-visible for testing.
     */
    IArchimateDiagramModel getView() {
        return view;
    }

    /**
     * Returns the target folder.
     * Package-visible for testing.
     */
    IFolder getFolder() {
        return folder;
    }
}
