package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.routing.ConnectionThroughNoteDetector;
import net.vheerden.archi.mcp.model.routing.EgressOutcome;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Assembles the structured/free-text warnings the {@code auto-route-connections} response
 * surfaces. Kept out of the accessor facade so the facade's line-count ratchet is unaffected
 * by warning-message wording.
 */
final class AutoRouteWarnings {

    private AutoRouteWarnings() {}

    /** MCP tool the caller should run to widen a layout-bound corridor before re-routing. */
    private static final String SPACING_REMEDY_TOOL = "apply-spacing-recommendations";

    /**
     * MCP tool the caller should run to enumerate the connection IDs that actually exist on the
     * view. Deliberately not {@code auto-route-connections}: re-running the tool that just reported
     * the miss cannot resolve it — only supplying a correct ID can.
     */
    private static final String CONNECTION_LOOKUP_TOOL = "get-view-contents";

    /**
     * Emits the connection-not-found warning for {@code connectionIds} that are not on the view.
     * Appends one free-text line per missing ID (the long-standing per-ID surface) plus a single
     * aggregate {@link StructuredWarningDto} whose {@code remediationViolatorIds} lists every
     * missing ID, so callers can react without parsing free text.
     *
     * <p>The structured code is what lets the response distinguish this condition from the several
     * unrelated routing conditions that also write to {@code warnings}; without it a caller can only
     * guess from list-emptiness, which is wrong whenever any other warning fires.</p>
     *
     * <p><strong>Note the deliberate asymmetry with the sibling emitters.</strong> They raise one
     * free-text line and repeat it verbatim as the structured message. This one cannot: the per-ID
     * free-text lines are a long-standing published surface that must stay exactly as they are, while
     * the structured warning is aggregate so that {@code remediationViolatorIds} can carry every
     * missing ID in a single entry. So the free text and the structured message are different strings
     * by design. The invariant callers actually rely on still holds — a non-empty
     * {@code structuredWarnings} still implies a non-empty {@code warnings}.</p>
     *
     * <p>No-op when {@code missingConnectionIds} is empty.</p>
     *
     * @param missingConnectionIds requested connection IDs that were not found on the view
     * @param warnings             the free-text warnings accumulator (mutated)
     * @param structuredWarnings   the structured warnings accumulator (mutated)
     */
    static void emitConnectionsNotFound(List<String> missingConnectionIds, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (missingConnectionIds == null || missingConnectionIds.isEmpty()) {
            return;
        }
        for (String connId : missingConnectionIds) {
            warnings.add("Connection not found on view: " + CallerText.bare(connId));
        }
        structuredWarnings.add(new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_NOT_FOUND,
                connectionsNotFoundMessage(missingConnectionIds, switch (arm) {
                    case APPLIED -> " The remaining connections were routed normally.";
                    case QUEUED -> " Nothing has been applied: the routing of the remaining "
                            + "connections is queued in the open batch.";
                    case AWAITING_APPROVAL -> " Nothing has been applied: the routing of the "
                            + "remaining connections is waiting on the human's decision, so those "
                            + "routes will only exist once it is approved.";
                }),
                CONNECTION_LOOKUP_TOOL,
                List.copyOf(missingConnectionIds)));
    }

    /**
     * Composes the connection-not-found message from the missing ids and ONE outcome clause.
     *
     * <p>The miss itself is arm-independent: the ids were looked up against the view before
     * anything was dispatched, so the count and the list are established whatever happens to the
     * model next and are carried unchanged on every arm. What varies is the single sentence about
     * what became of the connections that DID resolve — and it is composed, never edited into a
     * finished sentence, so the clause cannot acquire a second meaning by being spliced.</p>
     */
    private static String connectionsNotFoundMessage(List<String> missingConnectionIds,
            String outcomeClause) {
        return missingConnectionIds.size() + " requested connection ID(s) were not found "
                + "on the view and were skipped: "
                + String.join(", ", missingConnectionIds.stream().map(CallerText::bare).toList())
                + "." + outcomeClause + " Run get-view-contents to list "
                + "the connection IDs that exist on this view.";
    }

    /**
     * Records that the routing pass threw on degenerate geometry after the connection-not-found
     * warning had already been raised, and repairs that warning.
     *
     * <p>The connection-not-found message states what became of the connections that resolved. On
     * this path nothing became of them: the router threw, the early return carries no commands, and
     * the response would otherwise say "The remaining connections were routed normally" beside a
     * failure notice about the very same call. That contradiction is arm-INDEPENDENT — it is
     * equally false on an applied call — which is why it is repaired here rather than by the arm
     * selection above.</p>
     *
     * <p>The entry is RECOMPOSED from the ids it already carries in
     * {@code remediationViolatorIds}, not string-edited: the ids are the evidence, and rebuilding
     * the sentence around them is what keeps one clause from being spliced into another. The
     * per-ID free-text lines are untouched, because each of them is still true.</p>
     *
     * <p>The self-connection warning is repaired the same way and for the same reason: its loop
     * commands ride the compound this early return never builds, so a bare self-connection still
     * draws no line, whatever arm the call was on. Its free text is the structured message, so both
     * sinks are replaced.</p>
     *
     * @param loopedSelfConnectionIds the self-connections the call was going to give a loop
     */
    static void emitRoutingFailedDegenerateGeometry(List<String> loopedSelfConnectionIds,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        warnings.add("Routing failed due to degenerate element geometry (overlapping or "
                + "zero-gap elements). Use layout-flat-view or layout-within-group to "
                + "separate elements first, then re-route.");
        for (int i = 0; i < structuredWarnings.size(); i++) {
            StructuredWarningDto existing = structuredWarnings.get(i);
            if (StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(existing.code())) {
                structuredWarnings.set(i, new StructuredWarningDto(
                        existing.code(),
                        connectionsNotFoundMessage(existing.remediationViolatorIds(),
                                " Routing then failed on the remaining connections, so none of "
                                + "them were routed either."),
                        existing.remediationTool(),
                        existing.remediationViolatorIds()));
            }
            if (StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED.equals(existing.code())) {
                List<String> kept = new ArrayList<>(existing.remediationViolatorIds());
                kept.removeAll(loopedSelfConnectionIds);
                String message = selfConnectionsNotRoutedMessage("", kept, KEPT_AS_STORED,
                        loopedSelfConnectionIds,
                        " Routing then failed on the other connections, so the default loop was not "
                        + "written either, and these, stored with no bendpoints, still draw no line: ");
                warnings.set(warnings.indexOf(existing.message()), message);
                structuredWarnings.set(i, new StructuredWarningDto(existing.code(), message,
                        existing.remediationTool(), existing.remediationViolatorIds()));
            }
        }
    }

    /** MCP tool that edits one connection's bendpoints — the lever for a loop the router skips. */
    private static final String CONNECTION_EDIT_TOOL = "update-view-connection";

    /**
     * Emits the warning that names the self-connections a routing pass took out of its targets.
     *
     * <p>The router aims each end at the other end's centre, so for a connection from an object to
     * itself it would produce a stub and replace the loop; none is routed. What then happens to
     * one is Archi's rule applied to the bendpoints it holds when this call's compound runs: an
     * ArchiMate one holding none is given the default loop, and any other is left as it is. On the
     * applied arm that is straight after the prepare that sorted them, so the two lists are the
     * outcome and are stated as such: the kept ones are "left exactly as stored", which also covers
     * a self-connection that is not an ArchiMate connection and is never given a loop here, so it
     * does not claim the connection has bendpoints. On a deferred arm the compound runs later,
     * after the rest of the batch or the human's own edits, either of which can give a bare one
     * bendpoints or clear a kept one, so neither list is an outcome: the rule is stated, and the
     * lists are labelled as the reading taken when the call was made.</p>
     *
     * <p>{@code remediationViolatorIds} lists the kept ids first, then the looped ones. No-op when
     * both lists are empty.</p>
     *
     * @param keptIds   self-connections holding bendpoints, or not ArchiMate connections, when read
     * @param loopedIds ArchiMate self-connections holding no bendpoints when read
     */
    static void emitSelfConnectionsNotRouted(List<String> keptIds, List<String> loopedIds,
            DispatchArm arm, List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (keptIds.isEmpty() && loopedIds.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(keptIds);
        ids.addAll(loopedIds);
        String message = arm == DispatchArm.APPLIED
                ? selfConnectionsNotRoutedMessage("", keptIds, KEPT_AS_STORED, loopedIds,
                        " Stored with no bendpoints, and so drawing no line, these were given "
                        + "Archi's default loop: ")
                : selfConnectionsNotRoutedMessage(" Nothing has been applied. Which of them get "
                        + "Archi's default loop is decided " + (arm == DispatchArm.QUEUED
                                ? "when the open batch is committed" : "only once the human approves it")
                        + ", from the bendpoints each holds then: an ArchiMate connection holding none "
                        + "gets it, and every other is left as it is then.", keptIds,
                        " As read when this call was made, those not due a loop: ", loopedIds,
                        " As read when this call was made, those due one, stored with no bendpoints and "
                        + "so drawing no line: ");
        emitMirrored(StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED, message,
                CONNECTION_EDIT_TOOL, ids, warnings, structuredWarnings);
    }

    /**
     * {@code dto} with one entry naming the self-connections the layout's routing pass did not
     * route, or {@code dto} itself when there were none.
     *
     * <p>A layout call runs its routing pass several times and keeps one, so it names these once,
     * from the pass it keeps. The wording claims no stored state: in flat mode the layout drew
     * every loop the pass then saw, so "left exactly as stored" would describe the layout's loop,
     * not the caller's. On every arm the loop each keeps is the one it holds when the layout is
     * applied, and one holding none then is given Archi's default loop.</p>
     */
    static AutoLayoutAndRouteResultDto withSelfConnectionsKeptByLayout(AutoLayoutAndRouteResultDto dto,
            List<String> selfConnectionIds) {
        if (selfConnectionIds.isEmpty()) {
            return dto;
        }
        List<StructuredWarningDto> structured = new ArrayList<>(dto.structuredWarnings());
        emitMirrored(StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED, selfConnectionIds.size()
                + " self-connection(s) were not routed; each keeps its loop. Archi draws a connection "
                + "from an object to itself as a loop, which this router does not produce, so the "
                + "layout leaves each the loop it holds when the layout is applied, and gives one "
                + "holding no bendpoints then Archi's default loop: "
                + String.join(", ", selfConnectionIds) + ".", CONNECTION_EDIT_TOOL,
                selfConnectionIds, new ArrayList<>(), structured);
        return dto.withStructuredWarnings(List.copyOf(structured));
    }

    /**
     * {@code dto} with both layout disclosures: the self-connections the routing pass kept (as
     * {@link #withSelfConnectionsKeptByLayout}) and the notes the layout leaves objects over (as
     * {@link #withLayoutOverNotes}). One call per result site, so each site reads the same.
     */
    static AutoLayoutAndRouteResultDto withLayoutWarnings(AutoLayoutAndRouteResultDto dto,
            List<String> selfConnectionIds, IArchimateDiagramModel view, Command layout, DispatchArm arm,
            MutationDispatcher dispatcher, String sessionId) {
        return withLayoutOverNotes(withSelfConnectionsKeptByLayout(dto, selfConnectionIds), view, layout, arm,
                dispatcher, sessionId);
    }

    /**
     * {@code dto} with one {@link StructuredWarningCodes#LAYOUT_OVERLAPS_NOTE} entry naming every
     * note the layout leaves overlapping an object that is not a note, or {@code dto} itself when
     * there is none (and on a view without notes nothing is computed).
     *
     * <p>The geometry is the layout's: each object's rectangle is the one the last
     * {@code UpdateViewObjectCommand} in {@code layout} gives it, else its own, and an absolute
     * position climbs those rectangles through its containers. So the reading is the same on every
     * arm: once the layout is applied it is the view's, and in a batch or awaiting approval it is the
     * layout the call computed. A pair is judged as {@code assess-layout} judges it: boxes of no
     * width or height are not drawn and are skipped, the overlap is strict, and a note is not flagged
     * against the container that directly holds it.</p>
     *
     * <p>On the queued arm the objects are those the open batch will leave on the view
     * ({@link StagedViewState#objectsOn}): a note the batch placed earlier is there when the layout
     * is, though it is not on the view yet. An object the batch has not attached is climbed through
     * the container its add names, and a rectangle the batch's last bounds update gives an object is
     * its own unless the layout moves it. The other arms read the view as it is.</p>
     *
     * @param dispatcher the session's dispatcher, read on the queued arm only; may be null
     */
    static AutoLayoutAndRouteResultDto withLayoutOverNotes(AutoLayoutAndRouteResultDto dto,
            IArchimateDiagramModel view, Command layout, DispatchArm arm, MutationDispatcher dispatcher,
            String sessionId) {
        List<IDiagramModelObject> objects = new ArrayList<>();
        Map<String, IDiagramModelContainer> parents = Map.of();
        Map<IDiagramModelObject, int[]> laidOut = new java.util.HashMap<>();
        if (arm == DispatchArm.QUEUED && dispatcher != null) {
            StagedViewState staged = StagedViewState.of(dispatcher, sessionId, null);
            objects.addAll(staged.objectsOn(view));
            parents = staged.stagedParents();
            for (Command leaf : staged.leaves()) {
                if (leaf instanceof UpdateViewObjectCommand update && update.getDiagramObject() != null
                        && !NamedAxes.NONE.equals(update.getAxes())) {
                    laidOut.put(update.getDiagramObject(), new int[] { update.getNewX(), update.getNewY(),
                            update.getNewWidth(), update.getNewHeight() });
                }
            }
        } else {
            collect(view, objects);
        }
        if (objects.stream().noneMatch(o -> o instanceof IDiagramModelNote)) {
            return dto;
        }
        MutationContext.forEachCommand(layout, leaf -> {
            if (leaf instanceof UpdateViewObjectCommand update && update.getDiagramObject() != null) {
                laidOut.put(update.getDiagramObject(), new int[] { update.getNewX(), update.getNewY(),
                        update.getNewWidth(), update.getNewHeight() });
            }
        });
        List<String> noteIds = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        int count = 0;
        for (IDiagramModelObject note : objects) {
            int[] n = absolute(note, laidOut, parents);
            if (!(note instanceof IDiagramModelNote) || n[2] <= 0 || n[3] <= 0) {
                continue;
            }
            for (IDiagramModelObject other : objects) {
                int[] o = absolute(other, laidOut, parents);
                if (other instanceof IDiagramModelNote || other == parentOf(note, parents) || o[2] <= 0 || o[3] <= 0
                        || !(n[0] < o[0] + o[2] && n[0] + n[2] > o[0] && n[1] < o[1] + o[3] && n[1] + n[3] > o[1])) {
                    continue;
                }
                count++;
                if (!noteIds.contains(note.getId())) {
                    noteIds.add(note.getId());
                }
                if (pairs.size() < LayoutQualityAssessor.MAX_DESCRIPTIONS) {
                    pairs.add("note '" + note.getId() + "' overlaps " + (other instanceof IDiagramModelGroup
                            ? "group" : "element") + " '" + other.getId() + "'");
                }
            }
        }
        if (count == 0) {
            return dto;
        }
        String listed = String.join("; ", pairs) + (count > pairs.size() ? "; and " + (count - pairs.size())
                + " more" : "");
        String message = switch (arm) {
            case APPLIED -> "This layout left " + count + " note overlap(s): " + listed + ".";
            case QUEUED -> "As read when this call was made, from the layout it computed, the layout "
                    + "will leave " + count + " note overlap(s): " + listed + ". Nothing has been applied: "
                    + "this layout is queued in the open batch; end-batch with rollback:true discards it.";
            case AWAITING_APPROVAL -> "As read when this call was made, from the layout it computed, the "
                    + "layout will leave " + count + " note overlap(s): " + listed + ". Nothing has been "
                    + "applied: this layout is waiting on the human's decision, and rejecting it in Archi "
                    + "leaves the view as it is.";
        } + LAYOUT_OVER_NOTE_REMEDY;
        List<StructuredWarningDto> structured = new ArrayList<>(dto.structuredWarnings());
        emitMirrored(StructuredWarningCodes.LAYOUT_OVERLAPS_NOTE, message, NOTE_MOVE_REMEDY_TOOL,
                List.copyOf(noteIds), new ArrayList<>(), structured);
        return dto.withStructuredWarnings(List.copyOf(structured));
    }

    /** Why a layout lands on a note, and the remedy; the same on every arm, since it moves the note. */
    private static final String LAYOUT_OVER_NOTE_REMEDY = " Notes are not laid out: the layout moves every "
            + "other object and leaves each note where it was, so it can place objects over one. Counted per "
            + "(note, object) pair, as assess-layout's noteOverlapCount counts them. Move each note clear with "
            + AutoRouteWarnings.NOTE_MOVE_REMEDY_TOOL + ", or re-add it with add-note-to-view position below-content, which "
            + "places it below the content and every other note.";

    private static void collect(IDiagramModelContainer container, List<IDiagramModelObject> into) {
        for (IDiagramModelObject child : container.getChildren()) {
            into.add(child);
            if (child instanceof IDiagramModelContainer nested) {
                collect(nested, into);
            }
        }
    }

    /**
     * {@code object}'s rectangle in canvas coordinates, each rectangle on the climb as {@code laidOut}
     * gives it. An object not attached yet climbs through the container its staged add names in
     * {@code parents}; the climb is bounded against a malformed queue.
     */
    private static int[] absolute(IDiagramModelObject object, Map<IDiagramModelObject, int[]> laidOut,
            Map<String, IDiagramModelContainer> parents) {
        int[] own = rectangle(object, laidOut);
        int x = own[0];
        int y = own[1];
        int queuedHops = object.eContainer() == null ? 1 : 0;
        Object up = parentOf(object, parents);
        while (up instanceof IDiagramModelObject container && queuedHops <= parents.size()) {
            int[] offset = rectangle(container, laidOut);
            x += offset[0];
            y += offset[1];
            queuedHops += container.eContainer() == null ? 1 : 0;
            up = parentOf(container, parents);
        }
        return new int[] { x, y, own[2], own[3] };
    }

    /** The live container of {@code object}, or the one its staged add names when it is not attached yet. */
    private static Object parentOf(IDiagramModelObject object, Map<String, IDiagramModelContainer> parents) {
        EObject live = object.eContainer();
        return live != null ? live : parents.get(object.getId());
    }

    private static int[] rectangle(IDiagramModelObject object, Map<IDiagramModelObject, int[]> laidOut) {
        int[] staged = laidOut.get(object);
        return staged != null ? staged : new int[] { object.getBounds().getX(), object.getBounds().getY(),
                object.getBounds().getWidth(), object.getBounds().getHeight() };
    }

    /** The kept clause wherever nothing can change them before the response is read. */
    private static final String KEPT_AS_STORED =
            " Those not given a loop by this call are left exactly as stored: ";

    /**
     * Composes the self-connection message: the count, {@code rule} (empty where the lists are the
     * outcome), then each id list after the clause that ends where its ids begin.
     */
    private static String selfConnectionsNotRoutedMessage(String rule, List<String> keptIds,
            String keptClause, List<String> loopedIds, String loopedClause) {
        StringBuilder message = new StringBuilder((keptIds.size() + loopedIds.size())
                + " self-connection(s) were not routed: Archi draws a connection from an object to "
                + "itself as a loop, which this router does not produce.").append(rule);
        if (!keptIds.isEmpty()) {
            message.append(keptClause).append(String.join(", ", keptIds)).append('.');
        }
        if (!loopedIds.isEmpty()) {
            message.append(loopedClause).append(String.join(", ", loopedIds)).append('.');
        }
        return message.toString();
    }

    /**
     * Emits the layout-bound egress-lift warning when the terminal-clearance pass rolled back at
     * least one off-face lift. The router generated the lift(s) and then declined them because
     * applying them would narrow a parallel-connection gap below its healthy floor — a correct,
     * layout-bound decline. This surfaces that otherwise-silent decision so the caller widens the
     * corridor (the only remedy) instead of re-running the router (a no-op). Appends both a
     * machine-parseable {@link StructuredWarningDto} and a mirrored free-text line for back-compat.
     * No-op when {@code egressRolledBack <= 0}.
     *
     * @param egressRolledBack  rolled-back off-face egress-lift count from the routing result
     * @param warnings          the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitEgressLiftLayoutBound(int egressRolledBack, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (egressRolledBack <= 0) {
            return;
        }
        String message = egressRolledBack + " off-face terminal hug(s) could not be cleared without "
                + "narrowing a parallel-connection gap below the 15px healthy floor, so the router "
                + switch (arm) {
                    case APPLIED -> "kept the hug(s) in place.";
                    case QUEUED -> "will keep the hug(s) in place. Nothing has been applied: this "
                            + "routing is queued in the open batch.";
                    case AWAITING_APPROVAL -> "will keep the hug(s) in place. Nothing has been "
                            + "applied: this routing is waiting on the human's decision, so the "
                            + "hug(s) will only exist once it is approved.";
                }
                + EGRESS_LIFT_CORRIDOR_REMEDY;
        emitMirrored(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND, message, SPACING_REMEDY_TOOL,
                List.of(), warnings, structuredWarnings);
    }

    /**
     * Emits whichever egress warning the pass's outcome calls for: the layout-bound rollback
     * ({@link #emitEgressLiftLayoutBound}) or the pre-gate skip ({@link #emitEgressLiftPreGated}).
     * The outcome cannot hold both, so at most one is emitted. A {@code null} outcome emits nothing.
     */
    static void emitEgressOutcome(EgressOutcome egress, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (egress == null) {
            return;
        }
        emitEgressLiftLayoutBound(egress.rolledBack(), arm, warnings, structuredWarnings);
        emitEgressLiftPreGated(egress, arm, warnings, structuredWarnings);
    }

    /**
     * Emits the pre-gated egress warning when the terminal-clearance pass was skipped wholesale by
     * its view-level pre-gate AND at least one connection still hugs the face it leaves. A skip on a
     * view with no hug is not news and emits nothing. The gap printed is the value the gate compared,
     * measured on this call's routes as they stood when the pass ran. That is not the routes the view
     * held before the call, and not quite the routes it writes either: a later stage spreads shared
     * ports along their faces. So the message dates the gap to the pass, and {@code assess-layout}
     * can read a different gap on the written routes. The ids of the hugging connections ride as
     * {@code remediationViolatorIds}: counted on the first routing pass's clear routes after the
     * spread. A violated route written under {@code force} is not counted, and with
     * {@code autoNudge} the re-route's outcome is not reported and the first pass's routes are not
     * re-judged against the faces the nudge moved.
     */
    static void emitEgressLiftPreGated(EgressOutcome egress, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (egress == null || !egress.preGated() || egress.huggingConnectionIds().isEmpty()) {
            return;
        }
        int count = egress.huggingConnectionIds().size();
        String cause = "the router skipped its off-face lift pass for this view because, when it "
                + "reached that pass, its vertical connection runs sat too close together (10th-percentile gap "
                + gapPx(egress.preGateGapP10()) + "px, below the "
                + (int) EgressOutcome.PRE_GATE_FLOOR_PX + "px floor), so it ";
        String message = switch (arm) {
            case APPLIED -> count + " connection(s) still hug the face they leave: " + cause
                    + "left the hug(s) in place.";
            case QUEUED -> "In this routing " + count + " connection(s) hug the face they leave: " + cause
                    + "will leave the hug(s) in place. Nothing has been applied: this routing is "
                    + "queued in the open batch.";
            case AWAITING_APPROVAL -> "In this routing " + count + " connection(s) hug the face they "
                    + "leave: " + cause + "will leave the hug(s) in place. Nothing has been applied: "
                    + "this routing is waiting on the human's decision, so the hug(s) will only exist "
                    + "once it is approved.";
        } + EGRESS_PRE_GATE_REMEDY;
        emitMirrored(StructuredWarningCodes.EGRESS_LIFT_PRE_GATED, message, SPACING_REMEDY_TOOL,
                egress.huggingConnectionIds(), warnings, structuredWarnings);
    }

    /**
     * The gap to one decimal, rounded DOWN so a gap just under the floor never prints as the floor
     * itself (7.96 reads 7.9, not 8.0), and never as {@code -0.0}. The epsilon keeps a binary
     * shortfall such as 6.9999999 from printing as 6.9 — except within it of the floor, where it
     * would print the floor the gap fell below, so there the gap is truncated without it.
     */
    private static String gapPx(double gap) {
        double down = Math.floor(gap * 10 + 1e-6) / 10;
        if (gap < EgressOutcome.PRE_GATE_FLOOR_PX && down >= EgressOutcome.PRE_GATE_FLOOR_PX) {
            down = Math.floor(gap * 10) / 10;
        }
        return String.format(Locale.ROOT, "%.1f", down == 0 ? 0.0 : down);
    }

    /**
     * The pre-gate remedy. Like {@link #EGRESS_LIFT_CORRIDOR_REMEDY} it is about the layout, so it
     * holds on every arm; it names the parallel runs because the gate measures their spacing.
     */
    private static final String EGRESS_PRE_GATE_REMEDY =
            " Spread the parallel connection runs apart (increase element spacing) and re-route — "
            + "re-routing alone will not lift them.";

    /**
     * The corridor remedy, which is a statement about the LAYOUT rather than about recovering this
     * call, and therefore holds identically on all three arms. Kept as one constant so widening the
     * corridor cannot come to mean two different things depending on which arm composed it.
     */
    private static final String EGRESS_LIFT_CORRIDOR_REMEDY =
            " This is layout-bound: increase element spacing in the affected corridor and re-route "
            + "— re-routing alone will not clear it.";

    /**
     * Emits the net-zero disclosure for elements that {@code autoNudge} moved and that ended at the
     * position they started from.
     *
     * <p>A zero-displacement row in {@code nudgedElements} made the response contradict itself: the
     * connection the move targeted was still in {@code failed} and the identical move was still in
     * {@code recommendations}, while {@code nextSteps} counted the element as nudged. The row is now
     * filtered out of the reported list, and this code is what stops that filtering from turning a
     * visible contradiction into a silence.</p>
     *
     * <p><strong>The message states the outcome and declines to name a mechanism.</strong> A move
     * reversed by a later iteration and a move fully absorbed by the parent-containment clamp both
     * sum to zero and are indistinguishable in the accumulated map, so claiming either would be a
     * false statement whenever the other was the cause. It says so explicitly rather than leaving
     * the caller to assume the loop oscillated.</p>
     *
     * <p><strong>Cardinality is one entry per call</strong>, naming every element and carrying every
     * id in {@code remediationViolatorIds}, never truncated. The remedy is a spacing lever and
     * deliberately not {@code auto-route-connections}: re-running it recomputes the same
     * recommendation against the same geometry and reproduces the same zero.</p>
     *
     * <p>No-op when {@code netZeroElements} is empty.</p>
     *
     * @param netZeroElements    elements whose cumulative nudge displacement was {@code (0, 0)}
     * @param warnings           the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitNetZeroNudge(List<NudgedElementDto> netZeroElements, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (netZeroElements == null || netZeroElements.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        StringBuilder named = new StringBuilder();
        for (NudgedElementDto element : netZeroElements) {
            ids.add(element.viewObjectId());
            if (named.length() > 0) {
                named.append(", ");
            }
            String name = element.elementName();
            named.append(name != null && !name.isBlank() ? name : element.viewObjectId())
                    .append(" (").append(element.viewObjectId()).append(')');
        }
        String message = netZeroElements.size() + " element(s) processed by autoNudge "
                + switch (arm) {
                    case APPLIED -> "ended at their starting position (net displacement 0,0) and "
                            + "are therefore not reported in nudgedElements: " + named
                            + ". The recommended move did not take effect for them, so whatever it "
                            + "was meant to unblock may still be unresolved — check the failed "
                            + "array.";
                    case QUEUED -> "will end at their starting position (net displacement 0,0) and "
                            + "are therefore not reported in nudgedElements: " + named
                            + ". Nothing has been applied: this routing is queued in the open "
                            + "batch. The recommended move will not take effect for them even once "
                            + "it is committed, so whatever it was meant to unblock will still be "
                            + "unresolved — check the failed array.";
                    case AWAITING_APPROVAL -> "will end at their starting position (net "
                            + "displacement 0,0) and are therefore not reported in nudgedElements: "
                            + named + ". Nothing has been applied: this routing is waiting on the "
                            + "human's decision. The recommended move will not take effect for them "
                            + "even once it is approved, so whatever it was meant to unblock will "
                            + "still be unresolved — check the failed array.";
                }
                + NET_ZERO_MECHANISM_AND_REMEDY;
        emitMirrored(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO, message, SPACING_REMEDY_TOOL,
                List.copyOf(ids), warnings, structuredWarnings);
    }

    /**
     * What the response declines to claim, and the spacing lever that resolves it. Both are
     * arm-independent: the two producers of a zero are indistinguishable in the accumulated map
     * whatever becomes of the commands, and widening the spacing is a layout corrective rather than
     * a recovery of this call.
     */
    private static final String NET_ZERO_MECHANISM_AND_REMEDY =
            " This response does not distinguish a move absorbed by the parent-containment clamp "
            + "from one a later iteration reversed. Re-routing will reproduce the same result: "
            + "increase the spacing around these elements so a recommended move has room to take "
            + "effect.";

    /** MCP tool that moves an element, its neighbours or its parent by id, or an element out of its parent. */
    private static final String WITHHELD_MOVE_REMEDY_TOOL = "update-view-object";

    /**
     * Discloses the move recommendations {@link NudgeRecommendationScreen} withheld because the
     * parent-containment clamp would absorb them.
     *
     * <p><strong>Arm-blind.</strong> Withholding is decided while computing, on geometry already on
     * the view, so the sentence is the same on every arm and makes no claim about what was applied:
     * it describes a recommendation that was not offered, not a move that was or was not made.</p>
     *
     * <p>Names the parent and the side for each element: the floor is the parent's, so the parent
     * is where the caller looks. It does not tell the caller to move the parent and stop there. A
     * nested element's position is relative to its parent, so moving the parent carries every child
     * with it: that clears a route only when the blocked connection's other end lies outside the
     * parent, and the message says so and names the remedies that make room inside it.</p>
     *
     * <p>"Held by", not "sits at": a child already left of the padding line is held there too (the
     * flat floor would push it right), and it does not sit at the line.</p>
     *
     * <p>One entry per call, never truncated. No-op when nothing was withheld.</p>
     */
    /** "Name (id)", or the id alone when the object has no name of its own and was named by it. */
    private static String named(String name, String id) {
        return name == null || name.equals(id) ? String.valueOf(id) : name + " (" + id + ")";
    }

    static void emitRecommendationsWithheld(List<NudgeRecommendationScreen.Withheld> withheld,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (withheld == null || withheld.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        List<String> parentIds = new ArrayList<>();
        StringBuilder named = new StringBuilder();
        for (NudgeRecommendationScreen.Withheld w : withheld) {
            ids.add(w.elementId());
            if (w.parentId() != null && !parentIds.contains(w.parentId())) {
                parentIds.add(w.parentId());
            }
            List<String> sides = new ArrayList<>();
            List<String> directions = new ArrayList<>();
            for (NudgeRecommendationScreen.Floor floor : w.floors()) {
                sides.add(floor.side);
                directions.add(floor.direction);
            }
            named.append(named.length() > 0 ? "; " : "")
                    .append(named(w.elementName(), w.elementId())).append(" is held by the ")
                    .append(String.join(" and ", sides)).append(" of its parent ")
                    .append(named(w.parentName(), w.parentId()))
                    .append(", so a move ").append(String.join(" or ", directions))
                    .append(" cannot take effect inside it");
        }
        ids.addAll(parentIds);
        String message = withheld.size() + " move recommendation(s) withheld because the parent "
                + "holds the element on that side and would absorb the move: " + named
                + ". They are not in recommendations or blockedRecommendations, and autoNudge does "
                + "not attempt them, and re-routing against the same geometry withholds them again. "
                + "Moving or widening the parent does not make room inside it: a nested element's "
                + "position is relative to its parent, so every child moves with the parent. "
                + "Moving the parent with update-view-object therefore clears the route only when "
                + "the blocked connection's other end lies outside the parent. Otherwise make room "
                + "inside it: move the element, or the elements beside it, with "
                + "update-view-object, re-arrange the parent's contents with layout-within-group, "
                + "or move the element out of the parent.";
        emitMirrored(StructuredWarningCodes.MOVE_RECOMMENDATION_WITHHELD_BY_PARENT, message,
                WITHHELD_MOVE_REMEDY_TOOL, List.copyOf(ids), warnings, structuredWarnings);
    }

    /** MCP tool that moves a note by id — the remedy for a route the caller wants off a note. */
    private static final String NOTE_MOVE_REMEDY_TOOL = "update-view-object";

    /**
     * Emits the note-crossing disclosure for routes this pass applied straight through a note.
     *
     * <p>A note is deliberately not a routing obstacle, so this is a <strong>disclosure, not a
     * violation</strong>: nothing was broken and the router did exactly what its description says
     * it does. It does not belong in {@code violations}, whose only reason code speaks about a
     * crossed <em>element</em> and would be a false statement about a note. What was missing is
     * per-call attribution — the description warns at schema time that <em>a</em> crossing can
     * happen, and never says which one did.</p>
     *
     * <p><strong>Cardinality is one entry per call</strong>, naming every (connection, note) pair
     * and carrying the pair count in the message. Not one entry per crossing, and not one per
     * connection. {@code assess-layout} counts per (connection × visual), so a caller comparing
     * {@code structuredWarnings.length} against {@code connectionThroughNoteCount} would otherwise
     * read a disagreement that is not one; stating the pair count in the message is what makes the
     * two shapes reconcilable. The violator list and the message are <strong>never truncated</strong> —
     * a caller that cannot see the canvas must be able to act on each note individually.</p>
     *
     * <p>{@code remediationViolatorIds} carries the NOTE ids, de-duplicated in first-crossed order
     * (connections in routing order, notes in view order within each connection), because
     * the note is what the caller moves. The connection is named in the message by its label and
     * id; a note has no name to give — {@code IDiagramModelNote} carries content, not a name — so
     * it is named by id, which is also the vocabulary {@code assess-layout}'s own descriptions use
     * and the argument {@code update-view-object} takes.</p>
     *
     * <p>No-op when nothing was crossed: a view whose routes clear every note, and equally a view
     * with no notes at all, must gain no entry, no free-text line and no empty list.</p>
     *
     * <p><strong>Call this at the LAST point the routes can still change, never earlier.</strong>
     * On the orthogonal path that is after the auto-nudge pass, not before it: the nudge merges
     * freshly-routed connections into the routed-path map, rewrites the bendpoints of any
     * connection whose endpoint it moved, and re-collects the assessment nodes at the nudged
     * positions. A disclosure taken before it describes geometry the same call is about to
     * replace, and it is wrong in both directions — a crossing the nudge cleared would still be
     * named, and one the nudge introduced would not be.</p>
     *
     * @param routedPaths        absolute bendpoints per routed connection id, as applied
     * @param assessmentConns    the view's connections, read for endpoint ids and label text
     * @param nodes              the view's assessment nodes; notes and endpoint rectangles alike
     * @param warnings           the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitConnectionThroughNote(
            Map<String, List<AbsoluteBendpointDto>> routedPaths,
            List<AssessmentConnection> assessmentConns,
            List<AssessmentNode> nodes,
            DispatchArm arm,
            List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        if (routedPaths == null || routedPaths.isEmpty() || nodes == null
                || assessmentConns == null) {
            return;
        }
        List<ConnectionThroughNoteDetector.NoteRect> notes = new ArrayList<>();
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
            if (node.isNote()) {
                notes.add(new ConnectionThroughNoteDetector.NoteRect(
                        node.id(), node.x(), node.y(), node.width(), node.height()));
            }
        }
        if (notes.isEmpty()) {
            return;
        }

        Map<String, String> labelById = new LinkedHashMap<>();
        List<ConnectionThroughNoteDetector.RoutedPath> paths = new ArrayList<>();
        for (AssessmentConnection conn : assessmentConns) {
            List<AbsoluteBendpointDto> bendpoints = routedPaths.get(conn.id());
            if (bendpoints == null) {
                continue; // not routed by this pass
            }
            AssessmentNode src = nodeMap.get(conn.sourceNodeId());
            AssessmentNode tgt = nodeMap.get(conn.targetNodeId());
            if (src == null || tgt == null) {
                continue;
            }
            labelById.put(conn.id(), conn.labelText());
            paths.add(new ConnectionThroughNoteDetector.RoutedPath(
                    conn.id(), centreAnchoredPath(src, tgt, bendpoints), rectOf(src), rectOf(tgt)));
        }

        List<ConnectionThroughNoteDetector.NoteCrossing> crossings =
                ConnectionThroughNoteDetector.detect(
                        paths, notes, LayoutQualityAssessor.PASS_THROUGH_INSET);
        if (crossings.isEmpty()) {
            return;
        }

        List<String> noteIds = new ArrayList<>();
        StringBuilder pairs = new StringBuilder();
        for (ConnectionThroughNoteDetector.NoteCrossing crossing : crossings) {
            if (!noteIds.contains(crossing.noteId())) {
                noteIds.add(crossing.noteId());
            }
            if (pairs.length() > 0) {
                pairs.append("; ");
            }
            String label = labelById.get(crossing.connectionId());
            pairs.append("connection ");
            if (label != null && !label.isBlank()) {
                pairs.append('\'').append(label).append("' ");
            }
            pairs.append('(').append(crossing.connectionId()).append(") through note '")
                    .append(crossing.noteId()).append('\'');
        }

        String message = crossings.size()
                + switch (arm) {
                    case APPLIED -> " applied route(s) pass through a note: " + pairs
                            + ". A note is not a routing obstacle, so these routes were applied "
                            + "through it deliberately rather than failing. These are the "
                            + "crossings THIS CALL applied";
                    case QUEUED -> " route(s) computed by this call pass through a note: " + pairs
                            + ". A note is not a routing obstacle, so these routes will be applied "
                            + "through it deliberately rather than failing. Nothing has been "
                            + "applied: this routing is queued in the open batch. These are the "
                            + "crossings THIS CALL will apply once the batch is committed";
                    case AWAITING_APPROVAL -> " route(s) computed by this call pass through a "
                            + "note: " + pairs + ". A note is not a routing obstacle, so these "
                            + "routes will be applied through it deliberately rather than failing. "
                            + "Nothing has been applied: this routing is waiting on the human's "
                            + "decision. These are the crossings THIS CALL will apply once it is "
                            + "approved";
                }
                + THROUGH_NOTE_RECONCILIATION_AND_REMEDY;
        emitMirrored(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, message,
                NOTE_MOVE_REMEDY_TOOL, List.copyOf(noteIds), warnings, structuredWarnings);
    }

    /**
     * How this per-call figure reconciles with {@code assess-layout}'s whole-view one, and the
     * note-moving remedy. Both hold on every arm: the reconciliation describes two counting shapes
     * rather than two model states, and {@code update-view-object} moves a DIFFERENT, already
     * existing object — it is not a recovery of this call, so it does not vary with the arm.
     */
    private static final String THROUGH_NOTE_RECONCILIATION_AND_REMEDY =
            ", counted per (connection, note) pair on the same geometry assess-layout uses; its "
            + "connectionThroughNoteCount is a whole-view figure and also counts notes crossed by "
            + "connections this call did not route, plus element images, so it can be higher. Move "
            + "the note(s) clear with " + NOTE_MOVE_REMEDY_TOOL + " and re-assess; moving a note "
            + "changes the routes around it, so position notes after routing.";

    /**
     * Emits a free-text warning for each set of non-container elements sharing an identical
     * position.
     *
     * <p>Stacked elements are degenerate routing input — every route between them starts and ends
     * in the same place — so the router's output is meaningless before the layout is separated.
     * Elements are named rather than listed by id, resolved through the model, because the caller
     * cannot see the canvas and an id tells it nothing about which boxes to pull apart.
     *
     * <p>Free text only, deliberately: this is pre-existing published output with no structured
     * code, and giving it one here would be a response-contract change unrelated to the caller
     * that prompted the extraction.
     */
    static void emitStackedElements(List<AssessmentNode> nodes, IArchimateModel model,
            List<String> warnings) {
        Map<String, List<String>> positionMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            if (!node.isContainer()) {
                String posKey = (int) node.x() + "," + (int) node.y();
                // Resolve element name via model for meaningful warning messages
                String nodeName = node.id();
                EObject nodeObj = ArchimateModelUtils.getObjectByID(model, node.id());
                if (nodeObj instanceof IDiagramModelObject dmo && dmo.getName() != null) {
                    nodeName = dmo.getName();
                }
                positionMap.computeIfAbsent(posKey, k -> new ArrayList<>()).add(nodeName);
            }
        }
        for (Map.Entry<String, List<String>> entry : positionMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add("Stacked elements at position (" + entry.getKey()
                        + "): " + entry.getValue()
                        + ". Run layout-within-group first to separate them for cleaner routing.");
            }
        }
    }

    /**
     * Emits the same disclosure for {@code strategy: "clear"}, which applies straight
     * centre-to-centre lines by removing every bendpoint.
     *
     * <p>Easy to overlook, and the wrong way round: clearing a route makes a note crossing MORE
     * likely, not less. There is no pathfinder left to steer the line anywhere, so it goes
     * straight through whatever sits between the endpoints. An empty bendpoint list is not the
     * absence of an applied route — it IS the applied route, and {@code assess-layout} scores it
     * exactly that way.
     *
     * <p>Collects the view's own geometry rather than taking it from the caller, because the clear
     * branch never builds an obstacle set and so has no node list of its own to pass.
     *
     * @param targetConnections the connections this call cleared
     */
    static void emitClearedRoutesThroughNote(List<IDiagramModelConnection> targetConnections,
            IArchimateDiagramModel diagramModel, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (targetConnections == null || targetConnections.isEmpty()) {
            return;
        }
        Map<String, List<AbsoluteBendpointDto>> clearedPaths = new LinkedHashMap<>();
        for (IDiagramModelConnection conn : targetConnections) {
            if (conn instanceof IDiagramModelArchimateConnection archConn) {
                clearedPaths.put(archConn.getId(), List.of());
            }
        }
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        emitConnectionThroughNote(clearedPaths,
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes),
                nodes, arm, warnings, structuredWarnings);
    }

    /**
     * Rebuilds the path the router applied as {@code [sourceCentre, …bendpoints…, targetCentre]} —
     * the same shape {@code AssessmentCollector} produces when it reads the applied bendpoints back
     * out of the model, so the crossing verdict does not depend on which side of the write it is
     * taken from. The endpoints are element CENTRES here; the detector clips them to the perimeter,
     * which it must, because those first and last segments are never drawn.
     */
    private static List<double[]> centreAnchoredPath(AssessmentNode src, AssessmentNode tgt,
            List<AbsoluteBendpointDto> bendpoints) {
        List<double[]> path = new ArrayList<>(bendpoints.size() + 2);
        path.add(new double[]{src.x() + src.width() / 2, src.y() + src.height() / 2});
        for (AbsoluteBendpointDto bendpoint : bendpoints) {
            path.add(new double[]{bendpoint.x(), bendpoint.y()});
        }
        path.add(new double[]{tgt.x() + tgt.width() / 2, tgt.y() + tgt.height() / 2});
        return path;
    }

    /**
     * Writes ONE composed string to both sinks.
     *
     * <p>The two sinks are not a primary and a legacy copy — they are the same disclosure read by
     * two kinds of caller, and a caller triaging on the structured field must not be told something
     * the free-text field denies. Writing them from a single local is what makes a divergence
     * between them unrepresentable rather than merely unlikely; the previous shape repeated the
     * variable at two call sites, which is one edit away from rescoping only one of them.</p>
     */
    private static void emitMirrored(String code, String message, String remediationTool,
            List<String> violatorIds, List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        warnings.add(message);
        structuredWarnings.add(new StructuredWarningDto(code, message, remediationTool,
                violatorIds));
    }

    /** {@code [x, y, width, height]} for the detector's perimeter clip. */
    private static double[] rectOf(AssessmentNode node) {
        return new double[]{node.x(), node.y(), node.width(), node.height()};
    }

    /**
     * Resolves the display name for a crossed element ID.
     * Checks the viewObjectNameMap first, then falls back to model lookup.
     */
    static String resolveCrossedElementName(String crossedId,
            Map<String, String> viewObjectNameMap, IArchimateModel model) {
        if (crossedId == null) {
            return null;
        }
        String name = viewObjectNameMap.get(crossedId);
        if (name != null) {
            return name;
        }
        EObject obj = ArchimateModelUtils.getObjectByID(model, crossedId);
        if (obj instanceof IDiagramModelObject dmo) {
            return dmo.getName();
        }
        return crossedId; // fallback to ID if name can't be resolved
    }
}
