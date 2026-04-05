package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that creates an ArchiMate relationship: connects source/target
 * and adds to a folder (Story 7-2, B19).
 *
 * <p><strong>B19 fix:</strong> {@code connect()} is called inside {@code execute()},
 * not during preparation. This ensures EMF cross-references only exist after the
 * command runs on the command stack, preventing orphaned relationships.</p>
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
     * Creates a command to connect and add a relationship to a folder.
     *
     * <p>Source and target are passed explicitly because {@code connect()} has not
     * been called yet at construction time (B19: deferred connect).</p>
     *
     * @param relationship the relationship to connect and add (NOT yet connected)
     * @param folder       the target folder (typically the Relations folder)
     * @param source       the source element to connect
     * @param target       the target element to connect
     */
    public CreateRelationshipCommand(IArchimateRelationship relationship, IFolder folder,
            IArchimateElement source, IArchimateElement target) {
        this.relationship = relationship;
        this.folder = folder;
        this.source = source;
        this.target = target;
        setLabel("Create " + relationship.eClass().getName());
    }

    @Override
    public void execute() {
        relationship.connect(source, target);
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
