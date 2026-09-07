package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * GEF Command that removes a specialization (profile) definition from the
 * model's profile catalog.
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
 *
 * <p><strong>Execute-time usage re-check.</strong> Whether the deletion is
 * authorised is decided when the request is prepared, but on the deferred paths
 * — a queued batch, or a multi-operation bulk request — that decision is made
 * before any operation runs. An earlier operation in the same request can attach
 * the profile to a concept after this command was built, and a plain delete
 * would then remove it from the catalog while a live concept still references it.
 * So {@link #execute()} re-reads usage at execution time. Without {@code force}
 * it {@linkplain CommitSkippableCommand declines} when the profile is still in
 * use rather than orphaning it. With {@code force} it strips the profile from
 * each concept still carrying it before deleting, so a {@code force=true} delete
 * batched after the operation that attached the specialization still clears the
 * reference — the prepare-time usage snapshot that the accessor's force cascade
 * is built from is empty in that case, so the clearing must happen here, keyed on
 * execution-time state. To avoid collateral loss it declines instead if any such
 * concept also carries an <em>unrelated</em> profile the deletion was not
 * authorised to remove (the execution-time analogue of the accessor's prepare-time
 * multi-profile guard).</p>
 */
public class DeleteProfileCommand extends Command
        implements CommitSkippableCommand, NameCapturingCommand {

    private final IProfile profile;
    private final IArchimateModel model;
    private final int originalIndex;
    private final boolean force;

    /** Non-null once {@link #execute()} has declined to run. See {@link CommitSkippableCommand}. */
    private String skipReason;

    private String nameAtExecute;

    /** Whether the profile was actually removed, so {@link #undo()} reverses only what happened. */
    private boolean removed;

    /** Concepts the force path stripped the profile from, so {@link #undo()} can re-attach it. */
    private final List<IArchimateConcept> clearedConcepts = new ArrayList<>();

    /**
     * Creates a command to remove a profile from the model, declining if the profile is still
     * in use when it runs (no force).
     *
     * @param profile the profile to remove (must currently be in {@code model.getProfiles()})
     * @param model   the model to remove the profile from
     */
    public DeleteProfileCommand(IProfile profile, IArchimateModel model) {
        this(profile, model, false);
    }

    /**
     * Creates a command to remove a profile from the model.
     *
     * @param profile the profile to remove (must currently be in {@code model.getProfiles()})
     * @param model   the model to remove the profile from
     * @param force   when {@code true}, strip the profile from any concept still carrying it at
     *                execution time (unless that concept also carries an unrelated profile, in
     *                which case decline) rather than declining outright
     */
    public DeleteProfileCommand(IProfile profile, IArchimateModel model, boolean force) {
        this.profile = profile;
        this.model = model;
        this.force = force;
        this.originalIndex = model.getProfiles().indexOf(profile);
        setLabel("Delete specialization: " + profile.getName());
    }

    @Override
    public void execute() {
        removed = false;
        skipReason = null;
        // Cleared with the other per-run state and set only past the two declines below, so a run
        // that refuses reports no name and the name the operation was prepared with stands.
        nameAtExecute = null;
        clearedConcepts.clear();

        List<IArchimateConcept> usages = currentUsages();
        if (!usages.isEmpty()) {
            if (!force) {
                skipReason = describeRemainingUsage(usages.size());
                return;
            }
            String collateral = describeCollateralProfiles(usages);
            if (collateral != null) {
                skipReason = collateral;
                return;
            }
            for (IArchimateConcept concept : usages) {
                concept.getProfiles().remove(profile);
                clearedConcepts.add(concept);
            }
        }
        // Record whether this command actually removed the profile, so undo() reverses only what
        // it did. A batch that queues delete-specialization for the same profile twice runs two
        // commands against one catalog: the first removes it, the second finds it already gone
        // (remove() returns false). Both previously set removed=true, so the compound's
        // reverse-order undo re-added the profile twice into model.getProfiles() — a unique EMF
        // list that rejects duplicates and throws. Gating undo on the real outcome mirrors
        // DeleteFolderCommand's folderRemoved flag.
        // Read before the removal, kept only if the removal removed something. A duplicate delete
        // of the same profile in one call finds it already gone: it destroyed nothing, so it has
        // no moment of destruction to name, and the profile it points at may since have been
        // renamed on the detached object by another command in the same compound.
        String subjectName = profile.getName();
        removed = model.getProfiles().remove(profile);
        if (removed) {
            nameAtExecute = subjectName;
        }
    }

    @Override
    public void undo() {
        if (!removed) {
            return;
        }
        // Clamp to current size in case other profiles were removed since construction.
        int insertAt = Math.min(originalIndex, model.getProfiles().size());
        if (insertAt < 0) {
            insertAt = 0;
        }
        model.getProfiles().add(insertAt, profile);
        // Re-attach the profile to each concept the force path stripped it from.
        for (IArchimateConcept concept : clearedConcepts) {
            concept.getProfiles().add(profile);
        }
    }

    @Override
    public String getNameAtExecute() {
        return nameAtExecute;
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    /** Concepts currently carrying this profile. */
    private List<IArchimateConcept> currentUsages() {
        List<IArchimateConcept> usages = new ArrayList<>();
        for (Object u : ArchimateModelUtils.findProfileUsage(profile)) {
            if (u instanceof IArchimateConcept concept) {
                usages.add(concept);
            }
        }
        return usages;
    }

    /**
     * Re-checks, at execution time, that no concept still uses this profile. Any usage present now
     * arrived after the delete was authorised — from an earlier operation in the same batch or bulk
     * request — and removing the profile from the catalog would leave that concept referencing a
     * definition the model no longer holds.
     *
     * @return a plain-language reason to decline
     */
    private String describeRemainingUsage(int usageCount) {
        return "Specialization '" + profile.getName() + "' was still applied to " + usageCount
                + " concept" + (usageCount == 1 ? "" : "s") + " when the changes were applied, so it "
                + "was left in the model rather than deleted out from under them — an earlier "
                + "operation in the same request attached it. Delete it in a separate request, or "
                + "use force: true so those references are cleared in the same undoable operation.";
    }

    /**
     * Under {@code force}, re-checks that stripping this profile from its execution-time usages
     * would not also strip an unrelated profile some concept carries — collateral loss the delete
     * was never authorised to cause. Mirrors the accessor's prepare-time multi-profile guard, but
     * keyed on execution-time state so a deferred attach cannot defeat it.
     *
     * @return a plain-language reason to decline, or {@code null} when every usage concept carries
     *         only the profile being deleted
     */
    private String describeCollateralProfiles(List<IArchimateConcept> usages) {
        List<String> violations = new ArrayList<>();
        for (IArchimateConcept concept : usages) {
            List<String> others = new ArrayList<>();
            for (IProfile other : concept.getProfiles()) {
                if (other != profile) {
                    others.add("'" + other.getName() + "'");
                }
            }
            if (!others.isEmpty()) {
                violations.add("'" + concept.getName() + "' (" + concept.getId() + ") also carries "
                        + String.join(", ", others));
            }
        }
        if (violations.isEmpty()) {
            return null;
        }
        return "Specialization '" + profile.getName() + "' could not be force-deleted because "
                + "concepts it is applied to also carry other specializations this deletion was not "
                + "authorised to remove: " + String.join("; ", violations) + ". Detach those "
                + "explicitly, then retry the delete.";
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
