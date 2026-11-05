package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Finds a specialization by name as the work queued in a session's open batch will leave the model's
 * catalog. None of that work has run when a later operation is prepared, so the model alone answers
 * for the catalog as it was before it: a specialization the batch creates is not there yet, and one it
 * renames or deletes is still there under its old name.
 *
 * <p>Read-only: it replays the queued commands over the live catalog and changes neither.</p>
 */
final class StagedProfiles {

    private StagedProfiles() {
    }

    /**
     * The profile named {@code name} (case-insensitively, as Archi matches it) for {@code conceptType}
     * once the queued work has run: the live profiles, plus those a queued {@code create-specialization}
     * or inline specialization adds, each under the name the last queued rename gives it, less those a
     * queued {@code delete-specialization} removes. Null when there is none. With nothing queued it is
     * exactly Archi's own lookup.
     *
     * @param queued the open batch's queued leaf commands in order, or null outside a batch
     */
    static IProfile find(IArchimateModel model, List<Command> queued, String name, String conceptType) {
        if (queued == null || queued.isEmpty()) {
            return ArchimateModelUtils.getProfileByNameAndType(model, name, conceptType);
        }
        Map<IProfile, String> names = new LinkedHashMap<>();
        for (IProfile profile : model.getProfiles()) {
            names.put(profile, profile.getName());
        }
        for (Command leaf : queued) {
            if (leaf instanceof CreateProfileCommand create) {
                names.putIfAbsent(create.getProfile(), create.getProfile().getName());
            } else if (leaf instanceof ApplySpecializationCommand apply && apply.isNewProfile()) {
                names.putIfAbsent(apply.getProfile(), apply.getProfile().getName());
            } else if (leaf instanceof UpdateProfileCommand update && update.getNewName() != null
                    && names.containsKey(update.getProfile())) {
                names.put(update.getProfile(), update.getNewName());
            } else if (leaf instanceof DeleteProfileCommand delete) {
                names.remove(delete.getProfile());
            }
        }
        for (Map.Entry<IProfile, String> entry : names.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(name)
                    && conceptType.equals(entry.getKey().getConceptType())) {
                return entry.getKey();
            }
        }
        return null;
    }
}
