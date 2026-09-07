package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IProfile;

/**
 * GEF Command that removes all specialization profiles from an ArchiMate concept.
 *
 * <p>Used when update-element/update-relationship receives an empty string for
 * the specialization parameter, indicating all profiles should be cleared.</p>
 *
 * <p>Note: This only removes profiles from the concept, not from the model's
 * profile list. Orphaned profiles remain in the model for reuse.</p>
 *
 * <p><strong>Authorised-set guard.</strong> The force path of
 * {@code delete-specialization} reuses this command to strip the single profile
 * being deleted from each concept that carries it, relying on a prepare-time
 * guard that each such concept holds exactly that one profile. On the deferred
 * paths that guard can be defeated: an earlier operation in the same batch or
 * bulk request can attach a second, unrelated profile to the concept, and an
 * unconditional clear would then wipe it too. When constructed with an explicit
 * authorised set, {@link #execute()} therefore re-checks by identity and
 * {@linkplain CommitSkippableCommand declines} — leaving the concept untouched —
 * if it now carries any profile the delete was not authorised to remove. The
 * original single-argument constructor keeps the unconditional clear-all
 * semantics that update-element/update-relationship's replace flow depends on.</p>
 */
public class ClearSpecializationCommand extends Command implements CommitSkippableCommand {

    private final IArchimateConcept concept;
    private List<IProfile> previousProfiles;

    /**
     * The profiles this clear was authorised to remove, or {@code null} for the unconditional
     * clear-all used by update-element/update-relationship. Compared by object identity.
     */
    private final Set<IProfile> authorisedProfiles;

    /** Non-null once {@link #execute()} has declined to run. See {@link CommitSkippableCommand}. */
    private String skipReason;

    /**
     * Creates a command to clear all profiles from a concept, unconditionally.
     *
     * @param concept the concept to clear profiles from
     */
    public ClearSpecializationCommand(IArchimateConcept concept) {
        this(concept, null);
    }

    /**
     * Creates a command to clear a concept's profiles, declining if the concept carries any
     * profile outside {@code authorisedProfiles} when it runs.
     *
     * @param concept            the concept to clear profiles from
     * @param authorisedProfiles the profiles this clear is permitted to remove (by identity);
     *                           {@code null} restores the unconditional clear-all behaviour
     */
    public ClearSpecializationCommand(IArchimateConcept concept, Set<IProfile> authorisedProfiles) {
        this.concept = concept;
        this.authorisedProfiles = authorisedProfiles;
        setLabel("Clear specialization from: " + concept.getName());
    }

    @Override
    public void execute() {
        skipReason = describeUnauthorisedProfiles();
        if (skipReason != null) {
            previousProfiles = new ArrayList<>();
            return;
        }
        previousProfiles = new ArrayList<>(concept.getProfiles());
        concept.getProfiles().clear();
    }

    @Override
    public void undo() {
        // previousProfiles is captured lazily at execute() (above), so if the same concept is
        // cleared twice in one batch the later instance snapshots an already-empty list and this
        // addAll is a no-op — no duplicate re-add. Safe by that ordering, not by a guard here.
        concept.getProfiles().addAll(previousProfiles);
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    /**
     * Re-checks, at execution time, that the concept carries only profiles this clear was
     * authorised to remove. Anything else arrived after the delete was authorised — from an
     * earlier operation in the same request — and clearing it would be silent data loss.
     *
     * @return a plain-language reason to decline, or {@code null} when the clear is authorised
     *         (always {@code null} for the unconditional clear-all constructor)
     */
    private String describeUnauthorisedProfiles() {
        if (authorisedProfiles == null) {
            return null;
        }
        List<String> unauthorised = new ArrayList<>();
        for (IProfile p : concept.getProfiles()) {
            if (!authorisedProfiles.contains(p)) {
                unauthorised.add("'" + p.getName() + "'");
            }
        }
        if (unauthorised.isEmpty()) {
            return null;
        }
        return "Concept '" + concept.getName() + "' was left untouched because it now also carries "
                + String.join(", ", unauthorised) + ", which this specialization deletion was not "
                + "authorised to remove — an earlier operation in the same request attached it. "
                + "Detach or delete the other specialization(s) explicitly, then retry the delete.";
    }

    /**
     * Returns the concept this command operates on.
     * Package-visible for testing.
     */
    IArchimateConcept getConcept() {
        return concept;
    }
}
