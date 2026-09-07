package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;

/**
 * The connections an auto-connect pass declines to draw, and the containment question that decides
 * one of the two reasons.
 *
 * <p>Nesting is how a view expresses containment visually, and a connection drawn between an
 * object and something already inside it is a line that leaves a box and re-enters the same box —
 * a self-pass-through, which says nothing the nesting had not already said and which the layout
 * assessor flags as a defect. Two passes build connections between objects already on a view, so
 * the question is asked in one place and both answer it the same way.</p>
 *
 * <p>The record below is what a caller is told about a pair that was not drawn. A count would say
 * that something was declined and leave the agent unable to name any of it; the model relationship
 * survives either way, so an agent that disagrees with a skip has everything it needs here to draw
 * the connection itself.</p>
 */
final class AutoConnectSkip {

    /** One endpoint contains the other on the view, so the line would re-enter its own box. */
    static final String NESTING = "ancestor_descendant_on_view";

    /** The fifty-connection cap was already reached when this pair came up. */
    static final String CAP_REACHED = "auto_connect_cap_reached";

    private AutoConnectSkip() {
    }

    /**
     * Describes one declined pair, in the direction the connection would have been drawn.
     *
     * @param source the endpoint the connection would leave
     * @param target the endpoint it would reach
     * @param rel    the model relationship, which is preserved whichever reason applied
     */
    static AddToViewResultDto.SkippedConnection of(IDiagramModelObject source,
            IDiagramModelObject target, IArchimateRelationship rel, String reason) {
        return new AddToViewResultDto.SkippedConnection(source.getId(), target.getId(),
                rel.eClass().getName(), rel.getId(), reason);
    }

    /**
     * Returns true iff {@code possibleAncestor} appears in the view-containment
     * chain of {@code possibleDescendant} above {@code possibleDescendant} itself.
     *
     * <p>Walks {@code eContainer()} from {@code possibleDescendant} upward until
     * the chain reaches a non-{@link IDiagramModelObject} (the view root or null).
     * Returns false if the two arguments are the same instance or if either is null.
     *
     * <p>Asked by every pass that builds connections between objects already on a view, to skip a
     * pair where one endpoint contains the other — such a connection is, by construction, a
     * self-pass-through. The direction is not known in advance, so callers ask it both ways round.
     */
    static boolean isAncestorOnView(IDiagramModelObject possibleAncestor,
                                    IDiagramModelObject possibleDescendant) {
        if (possibleAncestor == null || possibleDescendant == null) {
            return false;
        }
        if (possibleAncestor == possibleDescendant) {
            return false;
        }
        EObject current = possibleDescendant.eContainer();
        while (current instanceof IDiagramModelObject) {
            if (current == possibleAncestor) {
                return true;
            }
            current = current.eContainer();
        }
        return false;
    }

    /**
     * Puts what a placement's auto-connect will and will not draw onto its approval card, and
     * returns the sentence a human reads beside it.
     *
     * <p>The card is the only description of the pending write that reaches a person: a proposal
     * carries {@code proposedChanges} and a summary onto the wire, never the prepared result, so a
     * list computed at prepare time and left in the DTO is invisible on the one surface an
     * authorisation happens from. Counting only what WILL be drawn was a complete account for as
     * long as this pass drew everything eligible. It stopped being one when the pass learned to
     * decline: a placement whose every candidate is nested proposes zero connections, and a card
     * that then says nothing about auto-connect asks a human to approve silent non-drawing.</p>
     *
     * <p>The pairs go on the card, never their count, and each list is omitted when empty rather
     * than sent as an empty one — a zero beside a description of what the field would have held
     * reads as a measured all-clear.</p>
     *
     * @param proposedChanges the card's structured disclosure, mutated in place
     * @param placement       the prepared result, whose skip lists are already populated
     * @return the summary sentence, naming every category that is not empty
     */
    static String discloseOnCard(Map<String, Object> proposedChanges, AddToViewResultDto placement) {
        List<AddToViewResultDto.SkippedConnection> nested = placement.skippedDueToNesting();
        List<AddToViewResultDto.SkippedConnection> capped = placement.skippedByCap();
        ProposalBuilder.putIfPresent(proposedChanges,
                "skippedDueToNesting", nested.isEmpty() ? null : nested,
                "skippedByCap", capped.isEmpty() ? null : capped);
        int drawn = (placement.autoConnections() != null) ? placement.autoConnections().size() : 0;
        StringBuilder summary = new StringBuilder("Element ready for placement on view.");
        if (drawn > 0) {
            summary.append(" ").append(drawn).append(" auto-connection(s) will be created.");
        }
        if (!nested.isEmpty()) {
            summary.append(" ").append(nested.size()).append(" will NOT be drawn: the placement "
                    + "nests this object inside an endpoint it relates to, and the nesting already "
                    + "expresses that relationship. The model relationship is preserved.");
        }
        if (!capped.isEmpty()) {
            summary.append(" ").append(capped.size())
                    .append(" more are dropped by the auto-connect cap.");
        }
        return summary.toString();
    }

    /**
     * Whether an object about to be placed will sit inside {@code possibleAncestor}.
     *
     * <p>The same question as above, asked for an object that is not in containment yet. A fresh
     * placement's {@code eContainer()} is null until its command runs, so walking up from the new
     * object would answer no to everything; its chain is the container it is about to be placed
     * into, and that container may itself be the endpoint in question.</p>
     *
     * <p>Only this direction can fire for a placement. The mirror — the new object containing the
     * endpoint — needs the new object to already have children, and a freshly created one has
     * none. That is why the caller here asks once where the sibling pass asks twice.</p>
     *
     * @param pendingParent    the container the object is being placed into; a view root means
     *                         top-level, and a top-level object nests inside nothing
     * @param possibleAncestor the endpoint the connection would reach
     */
    static boolean willNestInside(IDiagramModelContainer pendingParent,
                                  IDiagramModelObject possibleAncestor) {
        if (!(pendingParent instanceof IDiagramModelObject box)) {
            return false;
        }
        return box == possibleAncestor || isAncestorOnView(possibleAncestor, box);
    }
}
