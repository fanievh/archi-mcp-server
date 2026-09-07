package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Builds the human-facing approval-card text and cascade-count map for the destructive
 * {@code delete-view} / {@code delete-folder} tools.
 *
 * <p>Split out of {@link ArchiModelAccessorImpl} so the facade approval branch stays a thin
 * call (and the file's LOC ratchet is honoured — this logic must not live in the god-object
 * facade) and so the string/plural logic is unit-tested headlessly here rather than only
 * through the accessor.</p>
 *
 * <p><strong>Why the counts go into the description sentence:</strong> the approval card's
 * visible change row reads the mechanical {@code description} (see
 * {@code ApprovalCardModel.singleRow} — a delete proposal has no {@code effectDescription} and
 * no structured {@code name}), so a bare "Delete view: Foo" hides the blast radius. The cascade
 * counts therefore live in that sentence, not only in the {@code proposedChanges} map (which
 * surfaces only in the collapsed Technical-details / Copy-JSON disclosure). The sibling
 * {@code delete-element} / {@code delete-relationship} branches fold their counts in the same way.</p>
 */
final class DeleteApprovalCardText {

    private DeleteApprovalCardText() {
    }

    /**
     * The {@code delete-view} card sentence: names the view and both measured cascade counts —
     * {@code "Delete view: <name> (cascade: <V> view connection(s), <R> view reference(s))"}.
     *
     * <p>{@code relationshipsRemoved} is deliberately omitted: a view deletion removes no model
     * concepts, so that count is a correct, permanent {@code 0} (see {@code prepareDeleteView} and
     * the {@code delete-view-vestigial-cascade-counts} story) and would only add noise here.</p>
     */
    static String viewDescription(DeleteResultDto dto) {
        return "Delete view: " + dto.name()
                + " (cascade: " + count(dto.viewConnectionsRemoved(), "view connection")
                + ", " + count(dto.viewReferencesRemoved(), "view reference") + ")";
    }

    /**
     * Adds the two {@code delete-view} cascade counts to {@code proposedChanges} so they are
     * complete in the Technical-details disclosure and the per-card Copy-JSON payload.
     */
    static void putViewCounts(Map<String, Object> proposedChanges, DeleteResultDto dto) {
        proposedChanges.put("viewConnectionsRemoved", dto.viewConnectionsRemoved());
        proposedChanges.put("viewReferencesRemoved", dto.viewReferencesRemoved());
    }

    /**
     * The {@code delete-folder} card sentence. Under {@code force} it folds only the
     * <em>non-zero</em> cascade removals for legibility (an empty force-delete degrades to a bare
     * {@code "(force cascade)"}); a non-force delete — which requires an already-empty folder —
     * shows no cascade clause at all: {@code "Delete folder: <name>"}.
     */
    static String folderDescription(DeleteResultDto dto, boolean force) {
        String base = "Delete folder: " + dto.name();
        if (!force) {
            return base;
        }
        List<String> parts = new ArrayList<>();
        addCount(parts, dto.elementsRemoved(), "element");
        addCount(parts, dto.viewsRemoved(), "view");
        addCount(parts, dto.foldersRemoved(), "subfolder");
        addCount(parts, dto.relationshipsRemoved(), "relationship");
        addCount(parts, dto.viewReferencesRemoved(), "view reference");
        addCount(parts, dto.viewConnectionsRemoved(), "view connection");
        return base + " (force cascade"
                + (parts.isEmpty() ? "" : ": " + String.join(", ", parts)) + ")";
    }

    /**
     * Adds the three always-present primitive cascade counts to {@code proposedChanges} under a
     * force cascade. A non-force folder delete cascades nothing (the folder had to be empty to be
     * deletable), so they are omitted there to keep that card's raw payload minimal.
     */
    static void putFolderCounts(Map<String, Object> proposedChanges, DeleteResultDto dto,
            boolean force) {
        if (force) {
            proposedChanges.put("relationshipsRemoved", dto.relationshipsRemoved());
            proposedChanges.put("viewReferencesRemoved", dto.viewReferencesRemoved());
            proposedChanges.put("viewConnectionsRemoved", dto.viewConnectionsRemoved());
        }
    }

    /**
     * The six cascade counts a delete card can carry, paired with the noun each one is rendered
     * with. Ordered as {@link #folderDescription} renders them so a divergence sentence reads in the
     * same order as the card it contradicts.
     */
    private static final String[][] CASCADE_COUNTS = {
        { "elementsRemoved", "element" },
        { "viewsRemoved", "view" },
        { "foldersRemoved", "subfolder" },
        { "relationshipsRemoved", "relationship" },
        { "viewReferencesRemoved", "view reference" },
        { "viewConnectionsRemoved", "view connection" },
    };

    /**
     * Compares the cascade counts a delete card was <em>reviewed</em> with against the counts the
     * command rebuilt at approve-time would <em>actually</em> remove, and returns the human sentence
     * describing the disagreement — or {@code null} when they still agree.
     *
     * <p><strong>Why this check has to exist.</strong> The card is built from the propose-time DTO; the
     * command that runs is rebuilt against the current model at approve. Those are two different
     * measurements of the blast radius taken minutes apart, and only the first one is ever shown to a
     * human. The staleness guard does not close the gap: it fingerprints a target's own
     * <em>attributes</em>, and a folder gaining contents changes none of them — containment is an
     * {@code EReference}, and a folder has no bounds. So a folder proposed for a force-delete while
     * holding two elements vets as perfectly fresh after three more are dragged in, and the rebuild
     * quietly cascades over five. The human authorised two.
     *
     * <p>Comparing the two count sets directly is deliberately cause-agnostic: added containment, a
     * newly attached relationship, a nested subfolder and a retype all move a number, and all are
     * caught without teaching the shared {@code fingerprint} about any of them — which would also make
     * the propose sites that re-prepare reject changes they are designed to absorb. It is equally
     * agnostic about <em>who</em> moved the number: the human is reading a card that says two either
     * way. Any difference refuses, in both directions — a cascade that shrank was also not the one
     * reviewed, and re-proposing is cheap.</p>
     *
     * <p><strong>Counts are not identities, and this check cannot see the difference.</strong> A
     * <em>compensating swap</em> — one element leaving the folder while another arrives — leaves the
     * count untouched and passes. The staleness guard cannot cover for it either: it asks whether a
     * tracked object still <em>exists</em>, and an element moved elsewhere still resolves. Catching it
     * needs the cascade's set membership captured at propose and re-derived at approve, which is new
     * state and a new traversal rather than a tightening of either existing check. Pinned as it stands
     * by {@code DeleteFolderApprovalWindowTest#shouldNotDetectACompensatingSwap_knownResidual} so that
     * nothing here reads as coverage of it.</p>
     *
     * <p>Only counts the card actually carried are compared, so a non-force folder card (which
     * publishes none) and every non-deletion proposal return {@code null} untouched.</p>
     *
     * @param proposedChanges the propose-time card payload
     * @param rebuiltEntity   the approve-time rebuilt entity; only a {@link DeleteResultDto} is checked
     * @return the divergence sentence for the human, or {@code null} when the card still holds
     */
    static String cascadeDivergence(Map<String, Object> proposedChanges, Object rebuiltEntity) {
        if (proposedChanges == null || !(rebuiltEntity instanceof DeleteResultDto fresh)) {
            return null;
        }
        List<String> reviewed = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String[] entry : CASCADE_COUNTS) {
            Integer now = cascadeCount(fresh, entry[0]);
            if (!(proposedChanges.get(entry[0]) instanceof Integer approved) || now == null
                    || approved.equals(now)) {
                continue;
            }
            reviewed.add(count(approved, entry[1]));
            current.add(count(now, entry[1]));
        }
        if (reviewed.isEmpty()) {
            return null;
        }
        return "This proposal can no longer be applied as reviewed: it was approved for "
                + String.join(", ", reviewed) + ", but applying it now would remove "
                + String.join(", ", current) + ". Reject it and ask the agent to retry.";
    }

    /** Reads one named cascade count off a rebuilt {@link DeleteResultDto} ({@code null} if absent). */
    private static Integer cascadeCount(DeleteResultDto dto, String key) {
        return switch (key) {
            case "elementsRemoved" -> dto.elementsRemoved();
            case "viewsRemoved" -> dto.viewsRemoved();
            case "foldersRemoved" -> dto.foldersRemoved();
            case "relationshipsRemoved" -> dto.relationshipsRemoved();
            case "viewReferencesRemoved" -> dto.viewReferencesRemoved();
            case "viewConnectionsRemoved" -> dto.viewConnectionsRemoved();
            default -> null;
        };
    }

    /** {@code "N label"} / {@code "N labels"} with correct singular/plural. */
    private static String count(int n, String label) {
        return n + " " + label + (n == 1 ? "" : "s");
    }

    /** Appends {@link #count} to {@code parts} only when {@code n} is a positive, non-null value. */
    private static void addCount(List<String> parts, Integer n, String label) {
        if (n != null && n > 0) {
            parts.add(count(n, label));
        }
    }
}
