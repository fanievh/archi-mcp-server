package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IProfile;

/**
 * GEF Command that renames a specialization (profile) definition (Story C3c).
 *
 * <p>The profile's name is the only mutable identity field exposed by the
 * {@code update-specialization} tool — conceptType is part of the profile's
 * identity and cannot be changed in place. Renaming a profile automatically
 * propagates to every concept that references it (because concepts hold a
 * reference, not a copy of the name).</p>
 */
public class UpdateProfileCommand extends Command {

    private final IProfile profile;
    private final String newName;
    private final String oldName;

    /**
     * Creates a command to rename a profile.
     *
     * @param profile the profile to rename
     * @param newName the new name (must be non-blank; collision check is the accessor's job)
     */
    public UpdateProfileCommand(IProfile profile, String newName) {
        this.profile = profile;
        this.newName = newName;
        this.oldName = profile.getName();
        setLabel("Rename specialization: " + oldName + " \u2192 " + newName);
    }

    @Override
    public void execute() {
        profile.setName(newName);
    }

    @Override
    public void undo() {
        profile.setName(oldName);
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
    String getOldName() {
        return oldName;
    }

    /**
     * Package-visible for testing.
     */
    String getNewName() {
        return newName;
    }
}
