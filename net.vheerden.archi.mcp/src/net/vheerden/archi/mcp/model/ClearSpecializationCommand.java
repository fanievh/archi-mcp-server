package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IProfile;

/**
 * GEF Command that removes all specialization profiles from an ArchiMate concept (Story C3b).
 *
 * <p>Used when update-element/update-relationship receives an empty string for
 * the specialization parameter, indicating all profiles should be cleared.</p>
 *
 * <p>Note: This only removes profiles from the concept, not from the model's
 * profile list. Orphaned profiles remain in the model for reuse.</p>
 */
public class ClearSpecializationCommand extends Command {

    private final IArchimateConcept concept;
    private List<IProfile> previousProfiles;

    /**
     * Creates a command to clear all profiles from a concept.
     *
     * @param concept the concept to clear profiles from
     */
    public ClearSpecializationCommand(IArchimateConcept concept) {
        this.concept = concept;
        setLabel("Clear specialization from: " + concept.getName());
    }

    @Override
    public void execute() {
        previousProfiles = new ArrayList<>(concept.getProfiles());
        concept.getProfiles().clear();
    }

    @Override
    public void undo() {
        concept.getProfiles().addAll(previousProfiles);
    }

    /**
     * Returns the concept this command operates on.
     * Package-visible for testing.
     */
    IArchimateConcept getConcept() {
        return concept;
    }
}
