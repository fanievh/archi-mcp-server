package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Decides which connections an {@code add-to-view} placement draws when {@code autoConnect} is on, and
 * builds the command that draws them with the placement.
 *
 * <p>This is the one place that policy lives: which pairs are drawn, which are declined because the
 * placement nests inside the other end, and which fall past the fifty-connection cap. It reads nothing
 * itself. The caller hands it the objects that will be on the view, the relationships that will join the
 * placed element, and the nesting question, so a placement prepared in a batch and one made immediately
 * differ only in what they hand over.</p>
 *
 * <p>Each line is checked again when it runs: both ends must still be attached, and so must the
 * relationship. That is what makes it safe to draw a line to an object or for a relationship that only
 * work queued ahead of this placement will attach.</p>
 */
final class PlacementAutoConnect {

    /** The most connections one placement draws; the rest are reported as capped, never dropped silently. */
    static final int MAX_AUTO_CONNECTIONS = 50;

    private PlacementAutoConnect() {
    }

    /**
     * A relationship of the placed element and the concept at its other end. The other end is carried
     * rather than read from the relationship because a relationship queued earlier in a batch is not
     * connected to either end until its command runs.
     */
    record Joined(IArchimateRelationship relationship, IArchimateConcept otherEnd) {}

    /**
     * What the pass decided.
     *
     * @param command                the placement, with every drawn connection after it when on
     * @param autoConnections        the connections drawn, or null when {@code autoConnect} was off
     * @param skippedAutoConnections how many eligible pairs the cap dropped, or null when none
     * @param declined               the pairs not drawn because the placement nests inside the other end
     * @param cappedOut              the pairs the cap dropped
     */
    record Result(Command command, List<ViewConnectionDto> autoConnections, Integer skippedAutoConnections,
            List<AddToViewResultDto.SkippedConnection> declined, List<AddToViewResultDto.SkippedConnection> cappedOut) {}

    /**
     * Builds the placement's command and, when {@code autoConnect} is on, a connection from the new
     * object to every occurrence of each other end among {@code candidates}: the outgoing relationships
     * first, then the incoming ones, each in the order given.
     *
     * @param candidates  the element objects that will be on the view when the placement runs
     * @param outgoing    the relationships whose source is the placed element
     * @param incoming    the relationships whose target is the placed element
     * @param nestsInside whether the placement will sit inside the given object
     */
    static Result prepare(boolean autoConnect, IArchimateElement element, IDiagramModelArchimateObject diagramObj,
            IDiagramModelContainer parentContainer, Collection<IDiagramModelArchimateObject> candidates,
            List<Joined> outgoing, List<Joined> incoming, Predicate<IDiagramModelObject> nestsInside,
            IArchimateModel model) {
        List<AddToViewResultDto.SkippedConnection> declined = new ArrayList<>(), cappedOut = new ArrayList<>();
        if (!autoConnect) {
            return new Result(new AddToViewCommand(diagramObj, parentContainer), null, null, declined, cappedOut);
        }
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                "Add " + element.eClass().getName() + " to view with connections");
        compound.add(new AddToViewCommand(diagramObj, parentContainer));

        List<ViewConnectionDto> autoConnections = new ArrayList<>();
        int connectionCount = 0;
        int eligibleCount = 0;
        for (boolean fromPlacement : new boolean[] {true, false}) {
            for (Joined joined : fromPlacement ? outgoing : incoming) {
                IArchimateRelationship rel = joined.relationship();
                // every occurrence, as Archi's drop connects them all
                for (IDiagramModelArchimateObject other : ViewOccurrences.of(candidates, joined.otherEnd())) {
                    IDiagramModelArchimateObject source = fromPlacement ? diagramObj : other;
                    IDiagramModelArchimateObject target = fromPlacement ? other : diagramObj;
                    // The nesting this very placement is creating already says what the line would
                    // say, and the line would leave the box and re-enter it. The sibling
                    // auto-connect-view has declined this pair since it learned to see it; this
                    // path drew it, on exactly the shape this server's own guidance prescribes.
                    if (nestsInside.test(other)) {
                        declined.add(AutoConnectSkip.of(source, target, rel, AutoConnectSkip.NESTING));
                    } else if (connectionCount < MAX_AUTO_CONNECTIONS) {
                        eligibleCount++;
                        IDiagramModelArchimateConnection conn =
                                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                        conn.setArchimateRelationship(rel);
                        compound.add(RequireAttachedContainerCommand.drawing(guardEndpoint(
                                new AddConnectionToViewCommand(conn, source, target),
                                other, model, rel.getName()), rel, model));
                        autoConnections.add(new ViewConnectionDto(
                                conn.getId(), rel.getId(), rel.eClass().getName(),
                                source.getId(), target.getId(), null));
                        connectionCount++;
                    } else {
                        eligibleCount++;
                        cappedOut.add(AutoConnectSkip.of(source, target, rel, AutoConnectSkip.CAP_REACHED));
                    }
                }
            }
        }
        Integer skipped = eligibleCount > MAX_AUTO_CONNECTIONS ? eligibleCount - MAX_AUTO_CONNECTIONS : null;
        return new Result(compound, autoConnections, skipped, declined, cappedOut);
    }

    /**
     * The element's relationships as the live model holds them, in its own list order, less any no
     * longer in the model's containment.
     *
     * @param outgoing true for the relationships it is the source of, false for those it is the target of
     */
    static List<Joined> live(IArchimateElement element, boolean outgoing) {
        List<Joined> joined = new ArrayList<>();
        for (IArchimateRelationship rel : outgoing ? element.getSourceRelationships()
                : element.getTargetRelationships()) {
            // skip orphaned relationships (not in containment tree)
            if (rel.eContainer() != null) {
                joined.add(new Joined(rel, outgoing ? rel.getTarget() : rel.getSource()));
            }
        }
        return joined;
    }

    /**
     * Wraps a prepared connection so it declines if the endpoint it was resolved against is no
     * longer attached when it runs.
     *
     * <p>A connection has two endpoints and no container, so both are guarded by nesting two of
     * these rather than by teaching the guard to hold a pair. When the outer one declines the inner
     * never runs and contributes no reason, and when only one endpoint went missing only that one
     * speaks — so an operation still reports a single line either way.</p>
     */
    static Command guardEndpoint(Command cmd, EObject endpoint, IArchimateModel model, String subject) {
        return new RequireAttachedContainerCommand(cmd, endpoint, model, subject, "view object",
                RequireAttachedContainerCommand.Wording.CONNECT_ENDPOINT);
    }
}
