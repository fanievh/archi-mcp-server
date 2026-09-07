package net.vheerden.archi.mcp.model;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.model.IIdentifier;

/**
 * Extracts the set of pre-existing diagram-object / connection ids a <strong>compound</strong> proposal's
 * command actually touches, for the staleness guard.
 *
 * <p>The layout/route compounds the approval path freezes are built exclusively from this project's own
 * GEF commands — {@link UpdateViewObjectCommand}, {@link UpdateViewConnectionCommand},
 * {@link SetTextPositionCommand}, {@link SetTextRelativePositionCommand}, {@link AddConnectionToViewCommand}
 * — each of which exposes a typed
 * accessor for the model object it touches. (Archi's accessor-less {@code SetConstraintCommand} is never
 * used here, so the "GEF commands expose no touched-objects generically" caveat does not apply.) Walking
 * {@link CompoundCommand#getCommands()} therefore recovers every affected child id at propose-time with no
 * layout/route algorithm re-run — the broadened set the {@link ProposalStalenessGuard} then rejects-stale on
 * if the human deletes/edits/moves any tracked child during review.</p>
 *
 * <p>A connection an {@link AddConnectionToViewCommand} is <em>creating</em> is not yet in the model (its id
 * will not resolve at {@link ProposalStalenessGuard#capture}) — so it is skipped and its pre-existing
 * source/target endpoints are tracked instead. Any id the guard cannot resolve later degrades to the
 * {@link ProposalBuilder} rebuild-throw safety net, never an NPE.</p>
 *
 * <p>The three <em>add</em> commands follow that same rule for the same reason: the object being placed
 * does not exist yet, so what is tracked is the container it is being placed into. That container is the
 * thing an approval can outlive — it may be an object a human deletes during review, or one an enclosing
 * batch queued and then rolled back, and in both cases applying the frozen command afterwards would place
 * a child into something that is no longer there. This is what {@code bulk-mutate} was missing: it
 * fingerprinted each operation's own new id, which resolves nowhere at propose time, so its tracked set
 * came out empty and the guard had nothing to vet.</p>
 *
 * <p>Nested compounds are walked recursively. A placement is rarely a bare add — the group and note paths
 * wrap theirs in a placement guard, and the element path builds a compound when it also has fill recession
 * or auto-connect work to do — so without the recursion the arms above would see a {@code CompoundCommand}
 * and skip the add inside it.</p>
 *
 * <p>Package-private, {@code model/}-only, dependency-light (constructs no model and needs no OSGi runtime),
 * so it is covered headlessly by {@code CompoundChildTargetsTest} — unlike the OSGi-gated
 * {@code ArchiModelAccessorImpl} that calls it.</p>
 */
final class CompoundChildTargets {

    private CompoundChildTargets() {
        // static-only utility
    }

    /**
     * Seeds the result with the given anchor ids (the view id, and a container id for
     * {@code layout-within-group}), then unions the id of every pre-existing object the {@code compound}'s
     * child commands touch. Null/blank ids are filtered; insertion order is preserved (LinkedHashSet).
     */
    static Set<String> collect(CompoundCommand compound, String... anchorIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (anchorIds != null) {
            for (String id : anchorIds) {
                addId(ids, id);
            }
        }
        if (compound != null) {
            // GEF's CompoundCommand.getCommands() is a raw List on the Archi 5.7 target platform — iterate
            // as Object and let the instanceof ladder do the typing (avoids an unchecked-cast on the raw API).
            for (Object child : compound.getCommands()) {
                if (child instanceof UpdateViewObjectCommand u) {
                    addId(ids, u.getDiagramObject());
                } else if (child instanceof UpdateViewConnectionCommand u) {
                    addId(ids, u.getConnection());
                } else if (child instanceof SetTextPositionCommand s) {
                    addId(ids, s.getConnection());
                } else if (child instanceof SetTextRelativePositionCommand s) {
                    addId(ids, s.getConnection());
                } else if (child instanceof AddConnectionToViewCommand a) {
                    // Connection is being created (unresolvable until execute) — track its
                    // pre-existing endpoints so a human deleting/moving either one rejects-stale.
                    addId(ids, a.getSource());
                    addId(ids, a.getTarget());
                } else if (child instanceof AddToViewCommand a) {
                    // getView() is the TARGET CONTAINER, not the diagram root — the command holds an
                    // IDiagramModelContainer and the create path passes it the resolved parent, which
                    // is a nested group whenever parentViewObjectId was given. Named for the common
                    // case, so it reads like an exception to the two arms below and is not one.
                    addId(ids, a.getView());
                } else if (child instanceof AddGroupToViewCommand a) {
                    addId(ids, a.getParent());
                } else if (child instanceof AddNoteToViewCommand a) {
                    addId(ids, a.getParent());
                } else if (child instanceof CompoundCommand nested) {
                    ids.addAll(collect(nested));
                }
            }
        }
        return ids;
    }

    private static void addId(Set<String> ids, EObject obj) {
        if (obj instanceof IIdentifier identifiable) {
            addId(ids, identifiable.getId());
        }
    }

    private static void addId(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
    }
}
