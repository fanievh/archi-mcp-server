package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that adds an ArchiMate relationship to a folder (Story 7-2).
 *
 * <p>The relationship must be fully configured (name, source/target connected
 * via {@code connect()}) before this command is created. The command only
 * handles folder placement and removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class CreateRelationshipCommand extends Command {

    private final IArchimateRelationship relationship;
    private final IFolder folder;
    private final IArchimateElement source;
    private final IArchimateElement target;

    /**
     * Creates a command to add a relationship to a folder.
     *
     * <p>Captures source/target references at construction time so that
     * {@link #redo()} can reconnect after {@link #undo()} disconnects.</p>
     *
     * @param relationship the fully-configured relationship to add
     * @param folder       the target folder (typically the Relations folder)
     */
    public CreateRelationshipCommand(IArchimateRelationship relationship, IFolder folder) {
        this.relationship = relationship;
        this.folder = folder;
        this.source = (IArchimateElement) relationship.getSource();
        this.target = (IArchimateElement) relationship.getTarget();
        setLabel("Create " + relationship.eClass().getName());
    }

    @Override
    public void execute() {
        folder.getElements().add(relationship);
    }

    @Override
    public void redo() {
        relationship.connect(source, target);
        folder.getElements().add(relationship);
    }

    @Override
    public void undo() {
        folder.getElements().remove(relationship);
        relationship.disconnect();
    }

    /**
     * Returns the relationship this command will add.
     * Package-visible for testing.
     */
    IArchimateRelationship getRelationship() {
        return relationship;
    }

    /**
     * Returns the target folder.
     * Package-visible for testing.
     */
    IFolder getFolder() {
        return folder;
    }
}
