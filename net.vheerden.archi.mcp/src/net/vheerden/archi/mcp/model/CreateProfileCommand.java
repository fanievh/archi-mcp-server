package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * GEF Command that adds a specialization (profile) definition to the model's
 * profile catalog (Story C3c).
 *
 * <p>Used by the dedicated {@code create-specialization} tool. For inline
 * profile creation triggered by {@code create-element}/{@code create-relationship},
 * see {@link ApplySpecializationCommand} which adds the profile to the model
 * AND attaches it to the concept in one step.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@code MutationDispatcher}.</p>
 */
public class CreateProfileCommand extends Command {

    private final IProfile profile;
    private final IArchimateModel model;

    /**
     * Creates a command to add a profile to the model's profile list.
     *
     * @param profile the profile to add (must already have name and conceptType set)
     * @param model   the model to add the profile to
     */
    public CreateProfileCommand(IProfile profile, IArchimateModel model) {
        this.profile = profile;
        this.model = model;
        setLabel("Create specialization: " + profile.getName());
    }

    @Override
    public void execute() {
        model.getProfiles().add(profile);
    }

    @Override
    public void undo() {
        model.getProfiles().remove(profile);
    }

    /**
     * Returns the profile this command creates.
     * Package-visible for testing.
     */
    IProfile getProfile() {
        return profile;
    }
}
