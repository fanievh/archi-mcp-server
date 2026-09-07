package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that creates an ArchiMate relationship: connects source/target
 * and adds to a folder.
 *
 * <p><strong>Fix:</strong> {@code connect()} is called inside {@code execute()},
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
     * been called yet at construction time (deferred connect).</p>
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

    /**
     * Returns the element this command will connect the relationship's source end to.
     *
     * <p>Not the same question as {@code getRelationship().getSource()}, which is null until
     * {@code execute()} runs: between prepare and commit the relationship exists and is connected
     * to nothing. A caller validating a queued relationship against something else — the elements
     * behind the view objects a connection is being drawn between, say — has to ask the command
     * what it is going to connect, because the relationship itself does not know yet.</p>
     */
    IArchimateElement getSource() {
        return source;
    }

    /** The target end this command will connect, for the same reason as {@link #getSource()}. */
    IArchimateElement getTarget() {
        return target;
    }
}
