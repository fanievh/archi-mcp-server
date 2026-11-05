package net.vheerden.archi.mcp.model;

import java.util.Objects;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IProfile;

/**
 * GEF Command that updates a specialization (profile) definition.
 *
 * <p>Renames the profile via
 * {@link IProfile#setName(String)}. The profile's name is the only mutable
 * identity field exposed by the {@code update-specialization} tool —
 * conceptType is part of the profile's identity and cannot be changed in
 * place. Renaming a profile automatically propagates to every concept that
 * references it (because concepts hold a reference, not a copy of the
 * name).</p>
 *
 * <p>Extended to also update the
 * profile's {@code imagePath} (the specialization icon). The 3-arg
 * constructor accepts an {@link ImagePathChange} value encoding the three
 * possible operations (UNCHANGED / SET_TO / CLEAR). Both the rename and
 * the imagePath change are applied in one atomic, undoable execution.
 * Per EMF probe: {@link IProfile#setImagePath(String)} is
 * non-idempotent (raw {@code putfield} + {@code eNotify}) — this command
 * applies an idempotence guard before invoking the setter to avoid
 * spurious model-dirty flips on same-value sets.</p>
 *
 * <p>Newly-supplied fields are stored at construction time and applied on
 * {@code execute()}; original values are snapshotted in the ctor and
 * restored on {@code undo()}.</p>
 */
public class UpdateProfileCommand extends Command {

    private final IProfile profile;

    // Name change
    private final String newName;
    private final String oldName;
    private final boolean willChangeName;

    // imagePath change
    private final ImagePathChange imagePathChange;
    private final String oldImagePath;

    /**
     * Back-compat 2-arg ctor for rename-only operations.
     * Delegates to the canonical 3-arg ctor with {@link ImagePathChange#unchanged()}.
     *
     * @param profile the profile to update
     * @param newName the new name (must be non-blank when supplied; collision check is the accessor's job)
     */
    public UpdateProfileCommand(IProfile profile, String newName) {
        this(profile, newName, ImagePathChange.unchanged());
    }

    /**
     * Canonical 3-arg ctor. Either {@code newName} or
     * {@code imagePathChange} may be a no-op — the accessor's "at least one
     * of newName / imagePath / clearImagePath" guard ensures the command is
     * never constructed with no work to do.
     *
     * @param profile          the profile to update
     * @param newName          optional new name; null leaves the name unchanged
     * @param imagePathChange  optional imagePath operation; null treated as UNCHANGED
     */
    public UpdateProfileCommand(IProfile profile, String newName,
            ImagePathChange imagePathChange) {
        this.profile = profile;
        this.newName = newName;
        this.oldName = profile.getName();
        this.willChangeName = newName != null && !newName.equals(profile.getName());
        this.imagePathChange = (imagePathChange != null) ? imagePathChange
                : ImagePathChange.unchanged();
        this.oldImagePath = profile.getImagePath();
        setLabel(buildLabel());
    }

    private String buildLabel() {
        StringBuilder sb = new StringBuilder("Update specialization");
        if (willChangeName) {
            sb.append(": ").append(oldName).append(" → ").append(newName);
        } else {
            sb.append(": ").append(oldName);
        }
        if (imagePathChange.kind() != ImagePathChange.Kind.UNCHANGED) {
            sb.append(imagePathChange.kind() == ImagePathChange.Kind.CLEAR
                    ? " (clear icon)" : " (set icon)");
        }
        return sb.toString();
    }

    @Override
    public void execute() {
        if (willChangeName) {
            profile.setName(newName);
        }
        applyImagePath();
    }

    /**
     * Applies the imagePath change with an idempotence guard (EMF
     * probe: {@code setImagePath} is non-idempotent — same-value sets fire
     * spurious EMF notifications). Skip the setter call when the effective
     * new value already equals the current value.
     */
    private void applyImagePath() {
        if (imagePathChange.kind() == ImagePathChange.Kind.UNCHANGED) {
            return;
        }
        String newValue = (imagePathChange.kind() == ImagePathChange.Kind.CLEAR)
                ? null : imagePathChange.value();
        if (!Objects.equals(newValue, profile.getImagePath())) {
            profile.setImagePath(newValue);
        }
    }

    @Override
    public void undo() {
        if (willChangeName) {
            profile.setName(oldName);
        }
        if (imagePathChange.kind() != ImagePathChange.Kind.UNCHANGED) {
            // Restore original via direct setter — idempotence guard not needed on undo
            // (the only way we reach here is if execute() actually changed the value).
            if (!Objects.equals(oldImagePath, profile.getImagePath())) {
                profile.setImagePath(oldImagePath);
            }
        }
    }

    /** Package-visible for testing. Also read by {@link StagedProfiles}. */
    IProfile getProfile() {
        return profile;
    }

    /** Package-visible for testing. */
    String getOldName() {
        return oldName;
    }

    /** Package-visible for testing. Also read by {@link StagedProfiles}. */
    String getNewName() {
        return newName;
    }

    /** Package-visible for testing. */
    String getOldImagePath() {
        return oldImagePath;
    }

    /** Package-visible for testing. */
    ImagePathChange getImagePathChange() {
        return imagePathChange;
    }

    /**
     * Value type encoding the three possible operations on a profile's
     * {@code imagePath} field.
     *
     * <ul>
     *   <li>{@link Kind#UNCHANGED}: leave the imagePath untouched</li>
     *   <li>{@link Kind#SET_TO}: set the imagePath to {@link #value()}
     *       (must be a non-null, non-empty archive path)</li>
     *   <li>{@link Kind#CLEAR}: explicitly clear the imagePath (set null)</li>
     * </ul>
     */
    public static final class ImagePathChange {

        public enum Kind { UNCHANGED, SET_TO, CLEAR }

        private final Kind kind;
        private final String value;

        private ImagePathChange(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        public static ImagePathChange unchanged() {
            return new ImagePathChange(Kind.UNCHANGED, null);
        }

        public static ImagePathChange setTo(String value) {
            return new ImagePathChange(Kind.SET_TO, value);
        }

        public static ImagePathChange clear() {
            return new ImagePathChange(Kind.CLEAR, null);
        }

        public Kind kind() {
            return kind;
        }

        public String value() {
            return value;
        }
    }
}
