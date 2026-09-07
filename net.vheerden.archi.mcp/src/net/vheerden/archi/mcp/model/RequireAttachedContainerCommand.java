package net.vheerden.archi.mcp.model;

import java.util.Objects;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.INameable;

/**
 * Wraps a prepared mutation and declines it — whole — when the container it was prepared against
 * is no longer attached to the model by the time it executes.
 *
 * <p><strong>The defect it closes.</strong> A create resolves its target container while the
 * request is prepared. On the deferred paths — a queued batch, or a multi-operation bulk request —
 * every operation is prepared before any of them runs, so an operation earlier in the same request
 * can remove that container from the model afterwards. The removal is entirely legitimate at its
 * own turn (the container really was empty then), which is why no prepare-time check on either
 * operation can see the conflict: the truth is not yet false when it is checked. Without a
 * re-check here the create still runs, adding a real object to a container that is unreachable from
 * the model root — unsaved, gone on reload — while the response hands back a fresh id for it. See
 * {@link CommitSkippableCommand} for why this declines rather than throws.</p>
 *
 * <p><strong>Why it wraps rather than lives inside the created object's own command.</strong> A
 * create is not always one command. {@code create-element} with a specialization yields a compound
 * whose first child adds a brand-new profile to {@code model.getProfiles()} before the create runs,
 * so a check bolted onto the create alone would leave a specialization in the model belonging to an
 * element that never entered it — and nothing would undo it, because the batch commits. Guarding
 * the whole prepared command is the only shape that declines such a compound coherently.</p>
 *
 * <p><strong>Why it is a {@link CompoundCommand}.</strong> Both walks over a queued command tree —
 * the batch's own read-back of what it has queued, and the collection of skip reasons — recurse
 * into compounds and nothing else. Being one keeps a wrapped create visible to the read-back that
 * lets a later operation in the same batch address the object by id, with no change to either
 * walker. (A test harness that rebuilds compounds to run headless must rebuild this wrapper through
 * {@link #withGuarded} to keep the guard, rather than flattening it to a plain compound.)</p>
 *
 * <p>The model reference is captured at construction, while the container is still attached, for
 * the same reason {@code MoveToFolderCommand} captures it: derived lazily at execution the
 * container may already be detached and yield null, leaving nothing to compare against.</p>
 */
class RequireAttachedContainerCommand extends CompoundCommand implements CommitSkippableCommand {

    /**
     * Which sentence the decline is explained with.
     *
     * <p>The three differ by more than a noun. A create lands <em>inside</em> the thing that went
     * missing; a placement lands inside it too but is added to a view rather than created; a
     * connection is not added to its endpoints at all, it joins them — so a single verb parameter
     * would produce "the view object it was to be added to", which is false. Each wording is
     * therefore written out, once, here rather than at the six call sites, so the agent-facing
     * prose stays in one place and the model layer keeps owning it.</p>
     */
    enum Wording {
        /** Created inside a container that was removed — a folder, for {@code create-element}. */
        CREATE_IN_FOLDER,
        /** Added to a view, inside a container that was removed — the {@code add-*-to-view} family. */
        ADD_TO_CONTAINER,
        /** Joined two view objects, one of which was removed — {@code add-connection-to-view}. */
        CONNECT_ENDPOINT
    }

    private final EObject container;
    private final IArchimateModel model;
    private final String subject;
    private final String containerKind;
    private final Wording wording;

    /** Non-null once {@link #execute()} has declined to run. See {@link CommitSkippableCommand}. */
    private String skipReason;

    /** Whether the wrapped command actually ran, so {@link #undo()} reverses only what occurred. */
    private boolean ran;

    /**
     * @param guarded       the fully prepared command, wrappers and all
     * @param container     the container resolved at prepare time that must still be attached
     * @param model         the model the container belonged to when this was prepared
     * @param subject       what was to be created, for the agent-facing skip reason
     * @param containerKind the container's kind in plain words, e.g. {@code "folder"}
     * @param wording       which sentence explains the decline
     */
    RequireAttachedContainerCommand(Command guarded, EObject container, IArchimateModel model,
            String subject, String containerKind, Wording wording) {
        super(Objects.requireNonNull(guarded, "guarded must not be null").getLabel());
        this.container = Objects.requireNonNull(container, "container must not be null");
        this.model = model;
        this.subject = subject;
        this.containerKind = containerKind;
        this.wording = wording;
        add(guarded);
    }

    @Override
    public void execute() {
        skipReason = describeSkip();
        ran = skipReason == null;
        if (ran) {
            super.execute();
        }
    }

    @Override
    public void redo() {
        skipReason = describeSkip();
        ran = skipReason == null;
        if (ran) {
            super.redo();
        }
    }

    @Override
    public void undo() {
        // A declined command changed nothing, so there is nothing to reverse. Reversing anyway
        // would apply the create's undo to a create that never happened.
        if (ran) {
            super.undo();
        }
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    /**
     * Re-checks, at execution time, that the container this command was prepared against is still
     * reachable from the model it belonged to. Returns the reason to decline, or {@code null} when
     * the mutation is safe.
     */
    private String describeSkip() {
        if (model == null || isAttachedTo(container, model)) {
            return null;
        }
        String named = containerKind + " '" + displayName(nameOf(container)) + "'";
        return switch (wording) {
            case CREATE_IN_FOLDER -> "'" + displayName(subject) + "' was not created: the " + named
                    + " it was to be added to was removed from the "
                    + "model by an earlier operation in the same request. Anything created there would "
                    + "have been unreachable from the model and lost on reload, so nothing was created "
                    + "and nothing else was affected. Create it in a separate request, or name "
                    + article(containerKind) + containerKind + " the request does not delete.";
            case ADD_TO_CONTAINER -> "'" + displayName(subject) + "' was not added to the view: the "
                    + named + " it was to be added to was removed from the model by an earlier "
                    + "operation in the same request. Anything added there would have been "
                    + "unreachable from the model and lost on reload, so nothing was added and "
                    + "nothing else was affected. Add it in a separate request, or name "
                    + article(containerKind) + containerKind + " the request does not delete.";
            case CONNECT_ENDPOINT -> "'" + displayName(subject) + "' was not added to the view: the "
                    + named + " it connects was removed from the model by an earlier operation in "
                    + "the same request. A connection to an object the view no longer holds would "
                    + "have been unreachable from the model and lost on reload, so nothing was "
                    + "added and nothing else was affected. Add it in a separate request, or "
                    + "connect view objects the request does not remove.";
        };
    }

    /** Whether {@code object} still reaches {@code model} by containment. */
    private static boolean isAttachedTo(EObject object, IArchimateModel model) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            if (current == model) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(EObject object) {
        return (object instanceof INameable nameable) ? nameable.getName() : null;
    }

    /**
     * The indefinite article for a container kind, so the remedy reads as English.
     *
     * <p>The kinds are supplied by the call sites and one of them — {@code "element"} — takes
     * "an". Hard-coding "a" produced "name a element" on the only path where a placement's
     * container is an ArchiMate element, which no fixture covered and the live gate did.</p>
     */
    private static String article(String noun) {
        return (!noun.isEmpty() && "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0)
                ? "an " : "a ";
    }

    /** A never-null name for the agent-facing skip reason. */
    private static String displayName(String name) {
        return (name == null || name.isEmpty()) ? "(unnamed)" : name;
    }

    /**
     * Replaces the command this guard wraps, in place, and returns this same guard.
     *
     * <p>Package-visible for the headless test harnesses, which rebuild Archi's notifying compound
     * into a plain one to run outside the workbench. It rebuilds <em>in place</em> rather than
     * returning a copy for a reason that is easy to get wrong: a commit summary collects skip
     * reasons from the command objects the queue holds, so a harness that ran a copy would leave
     * the queued guard's reason unset and report a silent success for an operation that
     * declined.</p>
     */
    RequireAttachedContainerCommand withGuarded(Command replacement) {
        getCommands().clear();
        add(replacement);
        return this;
    }

    /** Package-visible for testing. */
    Command getGuarded() {
        return (Command) getCommands().get(0);
    }
}
