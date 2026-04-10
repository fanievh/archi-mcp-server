package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * GEF Command that assigns a specialization profile to an ArchiMate concept (Story C3b).
 *
 * <p>If the profile is newly created (not yet in the model's profile list),
 * this command also adds it to {@code model.getProfiles()} on execute
 * and removes it on undo.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher},
 * typically as part of a {@code NonNotifyingCompoundCommand}.</p>
 */
public class ApplySpecializationCommand extends Command {

    private final IArchimateConcept concept;
    private final IProfile profile;
    private final IArchimateModel model;
    private final boolean isNewProfile;

    /**
     * Creates a command to assign a specialization profile to a concept.
     *
     * @param concept      the concept to assign the profile to
     * @param profile      the profile to assign
     * @param model        the model (for adding new profiles to model.getProfiles())
     * @param isNewProfile true if the profile was just created and needs to be added to the model
     */
    public ApplySpecializationCommand(IArchimateConcept concept, IProfile profile,
            IArchimateModel model, boolean isNewProfile) {
        this.concept = concept;
        this.profile = profile;
        this.model = model;
        this.isNewProfile = isNewProfile;
        setLabel("Apply specialization: " + profile.getName());
    }

    @Override
    public void execute() {
        if (isNewProfile) {
            model.getProfiles().add(profile);
        }
        concept.getProfiles().add(profile);
    }

    @Override
    public void undo() {
        concept.getProfiles().remove(profile);
        // Defensive: only remove from model if still present. Protects against
        // double-undo or compound commands that share a new profile across multiple
        // ApplySpecializationCommand instances.
        if (isNewProfile && model.getProfiles().contains(profile)) {
            model.getProfiles().remove(profile);
        }
    }

    /**
     * Returns the concept this command operates on.
     * Package-visible for testing.
     */
    IArchimateConcept getConcept() {
        return concept;
    }

    /**
     * Returns the profile being assigned.
     * Package-visible for testing.
     */
    IProfile getProfile() {
        return profile;
    }

    /**
     * Returns whether this is a newly created profile.
     * Package-visible for testing.
     */
    boolean isNewProfile() {
        return isNewProfile;
    }
}
