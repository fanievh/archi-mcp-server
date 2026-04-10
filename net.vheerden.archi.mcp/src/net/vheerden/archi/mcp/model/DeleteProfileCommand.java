package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * GEF Command that removes a specialization (profile) definition from the
 * model's profile catalog (Story C3c).
 *
 * <p>The original index in {@code model.getProfiles()} is captured at
 * construction time so that {@link #undo()} can re-insert the profile at its
 * original position. The index is clamped to the current list size to handle
 * unusual undo orderings (e.g., other profiles also removed since this command
 * was constructed).</p>
 *
 * <p><strong>Force-delete cascade:</strong> When the {@code delete-specialization}
 * tool is invoked with {@code force=true}, this command is wrapped in a
 * {@code NonNotifyingCompoundCommand} together with one
 * {@link ClearSpecializationCommand} per usage concept. The accessor's
 * multi-profile guard guarantees each victim concept holds exactly one profile
 * (the one being deleted), so {@code ClearSpecializationCommand} is safe to
 * reuse here even though it clears all profiles from the concept.</p>
 */
public class DeleteProfileCommand extends Command {

    private final IProfile profile;
    private final IArchimateModel model;
    private final int originalIndex;

    /**
     * Creates a command to remove a profile from the model.
     *
     * @param profile the profile to remove (must currently be in {@code model.getProfiles()})
     * @param model   the model to remove the profile from
     */
    public DeleteProfileCommand(IProfile profile, IArchimateModel model) {
        this.profile = profile;
        this.model = model;
        this.originalIndex = model.getProfiles().indexOf(profile);
        setLabel("Delete specialization: " + profile.getName());
    }

    @Override
    public void execute() {
        model.getProfiles().remove(profile);
    }

    @Override
    public void undo() {
        // Clamp to current size in case other profiles were removed since construction.
        int insertAt = Math.min(originalIndex, model.getProfiles().size());
        if (insertAt < 0) {
            insertAt = 0;
        }
        model.getProfiles().add(insertAt, profile);
    }

    /**
     * Package-visible for testing.
     */
    IProfile getProfile() {
        return profile;
    }

    /**
     * Package-visible for testing.
     */
    int getOriginalIndex() {
        return originalIndex;
    }
}
