package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * {@code auto-route-connections} must name, in its own response, every connection whose applied
 * route penetrates a note.
 *
 * <p>A note is deliberately excluded from the A* obstacle set, so the router routes straight
 * through one that sits in a corridor. That exclusion is published and is not changed here. What
 * is new is the disclosure: the tool that CREATES the crossing knows the note is there, and until
 * now stayed silent while a second tool — {@code assess-layout} — discovered it afterwards and
 * reported a bare count whose ids lived only in prose.
 *
 * <p>The central claim is the AGREEMENT one, and it is asserted on BOTH sides of the boundary.
 * The two detectors do not share a geometry by default: {@code assess-layout} shrinks the note
 * rectangle inward by {@code PASS_THROUGH_INSET}, while the router grows its obstacles outward by
 * {@code DEFAULT_MARGIN} — a 20px-per-side disagreement. A tool that says "I crossed a note"
 * beside a metric that says the view is clean is worse than silence, because it teaches the agent
 * that one of its two instruments is lying and gives it no way to tell which. So the negative
 * fixture here is deliberately the SHARP one: a route passing 5px clear of the note's outer
 * rectangle is a crossing under the router's geometry and clean under the assessor's, and the
 * disclosure must follow the assessor.
 */
public class AutoRouteNoteCrossingDisclosureTest {

    private static final String SESSION = "note-crossing-session";
    private static final String VIEW_ID = "view-note-crossing";

    /** The corridor centre-line: hosts are 55 tall at y=200, so their centres sit at y≈227. */
    private static final int CORRIDOR_Y = 227;

    // ============ the crossing is named by the call that caused it ============

    @Test
    public void shouldNameTheConnectionAndTheNote_whenAnAppliedRouteCrossesANote() {
        AutoRouteResultDto dto = route(oneNoteInCorridor());

        StructuredWarningDto warning = crossingWarning(dto);
        assertNotNull("a route applied through a note must be disclosed by the call that applied it",
                warning);
        assertEquals("the remedy is to move the note, and update-view-object is the tool that"
                        + " moves a note by id",
                "update-view-object", warning.remediationTool());
        assertEquals("remediationViolatorIds carries the NOTE ids — the note is what the caller"
                        + " moves, not the connection",
                List.of("obj-note-a"), warning.remediationViolatorIds());
        assertTrue("the message must name the connection by id: " + warning.message(),
                warning.message().contains("conn-a"));
        assertTrue("the message must name the note by id: " + warning.message(),
                warning.message().contains("obj-note-a"));
    }

    @Test
    public void shouldRepeatTheDisclosureInTheFreeTextWarnings_forBackCompat() {
        AutoRouteResultDto dto = route(oneNoteInCorridor());
        StructuredWarningDto warning = crossingWarning(dto);
        assertNotNull("precondition: the structured warning fired", warning);
        assertTrue("StructuredWarningDto's own contract requires the paired free-text entry;"
                        + " warnings were " + dto.warnings(),
                dto.warnings().contains(warning.message()));
    }

    // ============ the report and the metric agree, on both sides of the boundary ============

    @Test
    public void shouldAgreeWithAssessLayout_whenTheRoutePenetratesTheNote() {
        Ctx c = oneNoteInCorridor();
        AutoRouteResultDto dto = route(c);
        AssessLayoutResultDto assessed = assess(c);

        assertEquals("the disclosure and the metric must agree on the crossing count",
                1, assessed.connectionThroughNoteCount());
        StructuredWarningDto warning = crossingWarning(dto);
        assertNotNull("assess-layout counted a crossing, so the routing call must have named it",
                warning);
        assertEquals("and they must agree on WHICH note",
                List.of("obj-note-a"), warning.remediationViolatorIds());
    }

    @Test
    public void shouldStaySilent_whenTheRoutePassesFivePixelsClearOfTheNote() {
        // The sharp case. 5px outside the note's outer rectangle is INSIDE the router's
        // expanded-by-10 obstacle and OUTSIDE the assessor's inset-by-10 interior. Following the
        // router's geometry here would fire a warning beside a connectionThroughNoteCount of 0.
        Ctx c = oneNoteFivePixelsClear();
        AutoRouteResultDto dto = route(c);
        AssessLayoutResultDto assessed = assess(c);

        assertEquals("the metric must read clean at 5px clearance",
                0, assessed.connectionThroughNoteCount());
        assertEquals("and the routing call must say nothing, or the two instruments disagree",
                null, crossingWarning(dto));
    }

    @Test
    public void shouldStaySilent_whenTheRouteGrazesTheNoteBorderWithoutPenetratingIt() {
        // The OTHER side of the assessor's geometry, and the one the 5px-clear fixture above does
        // not reach. Here the route runs INSIDE the note's outer rectangle but within the 10px
        // inset band, which assess-layout classifies as a border graze and deliberately NOT as a
        // through — the two are disjoint by construction, and connectionThroughNoteCount stays 0.
        // Without this fixture the inset is unpinned: dropping it entirely still passes every
        // other test here, because 5px OUTSIDE a rectangle is clear at any inset.
        Ctx c = oneNoteGrazedOnItsBorder();
        AutoRouteResultDto dto = route(c);
        AssessLayoutResultDto assessed = assess(c);

        assertEquals("precondition: this is a graze, so the through metric must read zero",
                0, assessed.connectionThroughNoteCount());
        assertTrue("precondition: and the graze detector must be the one that fires, or the"
                        + " fixture is not in the inset band at all",
                assessed.connectionGrazesVisualCount() >= 1);
        assertEquals("a graze is not a crossing — the disclosure must follow the metric's own"
                        + " classification, not merely its own idea of touching",
                null, crossingWarning(dto));
    }

    @Test
    public void shouldStaySilent_whenTheOnlyOverlapIsWithASegmentThatIsNeverDrawn() {
        // The perimeter clip, pinned. A routed path is built as [sourceCentre, …bendpoints…,
        // targetCentre], so its first and last segments run through the interiors of their own
        // endpoints — geometry Archi never draws, because the connection is rendered from the
        // element's perimeter. A note sitting over an endpoint therefore looks crossed to anyone
        // testing the raw path, and is not crossed on the canvas. That is not a hypothetical: the
        // legend a caller puts beside a hub is exactly where this bites.
        //
        // assess-layout clips before it tests, so it reads zero here. If the disclosure skipped the
        // clip it would fire, and the caller would be told to move a note that nothing crosses.
        Ctx c = noteOverAnEndpoint();
        AutoRouteResultDto dto = route(c);

        assertEquals("precondition: the metric sees nothing, because it clips first",
                0, assess(c).connectionThroughNoteCount());
        assertEquals("an overlap that exists only on an undrawn terminal segment is not a crossing",
                null, crossingWarning(dto));
    }

    @Test
    public void shouldDiscloseTheCrossing_whenTheStrategyIsClear() {
        // strategy 'clear' removes every bendpoint, leaving a straight centre-to-centre line. It
        // is easy to read that as "no route was applied" and skip the check — which is exactly
        // backwards. There is no pathfinder left to steer the line anywhere, so clearing makes a
        // note crossing MORE likely, not less, and assess-layout scores the straight line exactly
        // as it scores a routed one.
        Ctx c = oneNoteInCorridor();
        AutoRouteResultDto dto = routeWith(c, "clear", false);

        StructuredWarningDto warning = crossingWarning(dto);
        assertNotNull("a cleared straight line through a note is still an applied crossing",
                warning);
        assertEquals(List.of("obj-note-a"), warning.remediationViolatorIds());
        assertEquals("and it must agree with the metric on the applied geometry",
                1, assess(c).connectionThroughNoteCount());
    }

    @Test
    public void shouldDiscloseTheCrossing_whenTheModeIsTerminalsOnly() {
        // terminals-only is not a lesser mode for this purpose: it writes bendpoints and applies a
        // route like any other call, and it is the mode the tool's OWN regressed-crossings remedy
        // tells a caller to re-run with. A caller following that advice must not silently lose the
        // note disclosure on the way.
        Ctx c = diagonalTerminalsThroughANote();
        AutoRouteResultDto dto = routeTerminalsOnly(c);

        // PRECONDITION: terminals-only is a no-op on a connection whose terminals are already
        // orthogonal, and a call that applied nothing correctly discloses nothing. The fixture has
        // to give it something to rewrite, or the test proves only that a no-op is quiet.
        assertEquals("terminals-only must actually have rewritten a route here",
                1, dto.connectionsRouted());
        StructuredWarningDto warning = crossingWarning(dto);
        assertNotNull("terminals-only applies a route too, so it owes the same disclosure",
                warning);
        assertEquals(List.of("obj-note-a"), warning.remediationViolatorIds());
        assertEquals("and it must agree with the metric on the applied geometry",
                1, assess(c).connectionThroughNoteCount());
    }

    @Test
    public void shouldAgreeWithAssessLayout_onTheAutoNudgePath() {
        // Covers the autoNudge code path end to end and asserts the agreement invariant on it.
        //
        // STATED LIMIT, so this is not mistaken for more than it is: it does NOT pin the ordering
        // of the disclosure against the nudge. The nudge gate needs BOTH a failed connection and a
        // move recommendation, and no fixture reachable from this headless harness produces them —
        // A* finds a clear path around every blocker geometry tried here, so the nudge body never
        // executes. That is a pre-existing coverage gap this project has already recorded
        // elsewhere, not one introduced here. Verified by mutation: moving the disclosure back to
        // before the nudge leaves every test in this class green, precisely because the nudge does
        // not run. The ordering itself is argued from the code — the nudge merges fresh paths into
        // the routed-path map and re-collects nodes at nudged positions — and is documented on the
        // emitter.
        Ctx c = noteAndABlockedRoute();
        AutoRouteResultDto dto = routeWith(c, "orthogonal", true);

        int disclosed = crossingWarnings(dto).isEmpty()
                ? 0
                : countPairs(crossingWarning(dto).message());
        assertEquals("the disclosure must agree with the metric on the applied geometry",
                assess(c).connectionThroughNoteCount(), disclosed);
    }

    /** Pair count parsed back out of the message's leading figure. */
    private int countPairs(String message) {
        return Integer.parseInt(message.substring(0, message.indexOf(' ')));
    }

    // ============ cardinality is stated and pinned ============

    @Test
    public void shouldEmitExactlyOneEntryNamingEveryPair_whenOneConnectionCrossesThreeNotes() {
        Ctx c = oneConnectionThroughThreeNotes();
        AutoRouteResultDto dto = route(c);

        assertEquals("assess-layout counts per (connection x visual), so three notes on one route"
                        + " is THREE there — but the disclosure is ONE entry carrying all three,"
                        + " never one entry per crossing",
                1, crossingWarnings(dto).size());
        StructuredWarningDto warning = crossingWarning(dto);
        assertEquals("every crossed note id must be present, untruncated",
                List.of("obj-note-a", "obj-note-b", "obj-note-c"),
                warning.remediationViolatorIds());
        assertTrue("the pair count must be stated in the message so a caller comparing it against"
                        + " connectionThroughNoteCount reads agreement, not a phantom mismatch: "
                        + warning.message(),
                warning.message().contains("3"));
        assertEquals("and it must agree with the metric",
                3, assess(c).connectionThroughNoteCount());
    }

    @Test
    public void shouldEmitExactlyOneEntryNamingEveryPair_whenThreeConnectionsCrossOneNote() {
        Ctx c = threeConnectionsThroughOneNote();
        AutoRouteResultDto dto = route(c);

        assertEquals("one entry per CALL, not one per connection",
                1, crossingWarnings(dto).size());
        StructuredWarningDto warning = crossingWarning(dto);
        assertEquals("one note crossed three times is still one note to move",
                List.of("obj-note-a"), warning.remediationViolatorIds());
        for (String connId : List.of("conn-a", "conn-b", "conn-c")) {
            assertTrue("every crossing connection must be named: " + warning.message(),
                    warning.message().contains(connId));
        }
        assertEquals("and it must agree with the metric",
                3, assess(c).connectionThroughNoteCount());
    }

    // ============ silence when there is nothing to say ============

    @Test
    public void shouldAddNothingAtAll_whenTheViewCarriesNoNote() {
        AutoRouteResultDto dto = route(noNotes());

        assertEquals("a view without notes must not gain a warning", 0, crossingWarnings(dto).size());
        assertEquals("nor any structured warning at all on this otherwise-clean fixture",
                List.of(), dto.structuredWarnings());
        assertEquals("nor any free-text warning", List.of(), dto.warnings());
    }

    @Test
    public void shouldAddNothing_whenAViewHasANoteTheRouteDoesNotTouch() {
        AutoRouteResultDto dto = route(oneNoteFivePixelsClear());
        assertEquals("the presence of a note is not the trigger — crossing it is",
                List.of(), dto.structuredWarnings());
    }

    // ==================== fixtures ====================

    /** One horizontal route; one note straddling it. */
    private Ctx oneNoteInCorridor() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", "Legend", 380, CORRIDOR_Y - 57, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /** Same route; the note's TOP edge sits 5px below the corridor centre-line. */
    private Ctx oneNoteFivePixelsClear() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", "Legend", 380, CORRIDOR_Y + 5, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * Same route; the note's top edge is 7px ABOVE the corridor centre-line, so the route runs
     * inside the note's outer rectangle but within its 10px inset band — a border graze.
     */
    private Ctx oneNoteGrazedOnItsBorder() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", "Legend", 380, CORRIDOR_Y - 7, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * A note laid over the SOURCE element. The unclipped first segment runs from the host's centre
     * (60,227) outward and would cross the note's inset interior; the drawn segment starts at the
     * host's perimeter (120,227), which is clear of the note entirely.
     */
    private Ctx noteOverAnEndpoint() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", "Legend", 0, 200, 110, 55);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * A note in the corridor plus a blocking element sitting squarely on the straight path, so the
     * first routing pass produces a constraint violation and autoNudge has something to act on.
     */
    private Ctx noteAndABlockedRoute() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addHost(c, "Blocker", "bk", 400, 0, 120, 460);
        addNote(c, "obj-note-a", "Legend", 200, CORRIDOR_Y - 57, 160, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * A connection carrying an interior bendpoint well below the endpoint line, so both terminal
     * segments leave their elements diagonally — the shape terminals-only exists to rectify. A
     * wide note straddles the resulting path.
     */
    private Ctx diagonalTerminalsThroughANote() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", "Legend", 380, CORRIDOR_Y - 57, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        // Absolute (460,420) expressed relative to both endpoint centres (60,227) and (860,227).
        setBendpoint(c, 460 - 60, 420 - 227, 460 - 860, 420 - 227);
        return c;
    }

    private void setBendpoint(Ctx c, int startX, int startY, int endX, int endY) {
        IDiagramModelBendpoint bp = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
        bp.setStartX(startX);
        bp.setStartY(startY);
        bp.setEndX(endX);
        bp.setEndY(endY);
        findConnection(c.view).getBendpoints().add(bp);
    }

    private IDiagramModelConnection findConnection(IDiagramModelContainer container) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                return conn;
            }
        }
        throw new AssertionError("no connection on the view");
    }

    /** One long route crossing three notes in a row. */
    private Ctx oneConnectionThroughThreeNotes() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 1600, 200, 120, 55);
        addNote(c, "obj-note-a", "One", 300, CORRIDOR_Y - 57, 200, 120);
        addNote(c, "obj-note-b", "Two", 700, CORRIDOR_Y - 57, 200, 120);
        addNote(c, "obj-note-c", "Three", 1100, CORRIDOR_Y - 57, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /** Three parallel routes, one tall note straddling all three. */
    private Ctx threeConnectionsThroughOneNote() {
        Ctx c = newView();
        addHost(c, "Upper Left", "l1", 0, 100, 120, 55);
        addHost(c, "Upper Right", "r1", 800, 100, 120, 55);
        addHost(c, "Middle Left", "l2", 0, 300, 120, 55);
        addHost(c, "Middle Right", "r2", 800, 300, 120, 55);
        addHost(c, "Lower Left", "l3", 0, 500, 120, 55);
        addHost(c, "Lower Right", "r3", 800, 500, 120, 55);
        addNote(c, "obj-note-a", "Tall legend", 380, 80, 200, 500);
        connect(c, "conn-a", "rel-a", "obj-l1-n", "obj-r1-n");
        connect(c, "conn-b", "rel-b", "obj-l2-n", "obj-r2-n");
        connect(c, "conn-c", "rel-c", "obj-l3-n", "obj-r3-n");
        return c;
    }

    /** The regression pin's fixture: identical routing geometry, no note anywhere. */
    private Ctx noNotes() {
        Ctx c = newView();
        addHost(c, "Left Host", "hl", 0, 200, 120, 55);
        addHost(c, "Right Host", "hr", 800, 200, 120, 55);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    // ==================== reading the response ====================

    private StructuredWarningDto crossingWarning(AutoRouteResultDto dto) {
        List<StructuredWarningDto> found = crossingWarnings(dto);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<StructuredWarningDto> crossingWarnings(AutoRouteResultDto dto) {
        List<StructuredWarningDto> found = new ArrayList<>();
        for (StructuredWarningDto w : dto.structuredWarnings()) {
            if (StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE.equals(w.code())) {
                found.add(w);
            }
        }
        return found;
    }

    // ==================== driving ====================

    private AutoRouteResultDto route(Ctx c) {
        return routeWith(c, "orthogonal", false);
    }

    private AutoRouteResultDto routeTerminalsOnly(Ctx c) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, c.model);
        try {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false,
                    false, 0, 0, "terminals-only").entity();
        } finally {
            accessor.dispose();
        }
    }

    private AutoRouteResultDto routeWith(Ctx c, String strategy, boolean autoNudge) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, c.model);
        try {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, null, strategy, false,
                    autoNudge, 0, 0, null).entity();
        } finally {
            accessor.dispose();
        }
    }

    private AssessLayoutResultDto assess(Ctx c) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, c.model);
        try {
            return accessor.assessLayout(VIEW_ID, true);
        } finally {
            accessor.dispose();
        }
    }

    // ==================== fixture plumbing ====================

    private static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
    }

    private Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setName("NoteCrossing");
        c.model.setId("model-note-crossing");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.view.setName("NoteCrossing");
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    /**
     * Elements, notes and relationships here are all left UNNAMED, and that is load-bearing rather
     * than lazy. {@code AssessmentCollector} measures a label through SWT to pre-compute its width,
     * and on a display-less Linux CI runner that call throws {@code SWTError: No more handles
     * [gtk_init_check() failed]}. {@code SWTError} extends {@code Error}, not {@code Exception}, so
     * the collector's {@code catch (Exception e)} does not catch it — while the same call on macOS
     * throws {@code SWTException}, which IS caught. A named fixture therefore passes locally and
     * errors in CI. The measurement is skipped entirely when the name is empty and the note content
     * is blank, which is why the sibling routing regression fixtures name nothing either. Nothing
     * asserted in this class reads a name: the disclosure is asserted on ids.
     *
     * @param label documentation only — deliberately NOT written to the model, see above
     */
    private void addHost(Ctx c, String label, String id, int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id + "-n");
        c.model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id + "-n");
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, w, h);
        c.view.getChildren().add(obj);
    }

    /**
     * Content is left blank for the reason given on {@link #addHost}: a non-blank note is measured
     * through SWT, which is an {@code Error} rather than an {@code Exception} on a display-less
     * runner. A note is a note by type, not by content, so the geometry under test is unaffected.
     *
     * @param label documentation only — deliberately NOT written to the model
     */
    private void addNote(Ctx c, String id, String label, int x, int y, int w, int h) {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId(id);
        note.setBounds(x, y, w, h);
        c.view.getChildren().add(note);
    }

    /**
     * Built directly rather than through {@code createRelationship}: that path consults Archi's
     * static validity matrix, which needs an OSGi context this fixture does not have.
     */
    private void connect(Ctx c, String connId, String relId,
            String sourceObjId, String targetObjId) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = findObject(c.view, sourceObjId);
        IDiagramModelArchimateObject tgt = findObject(c.view, targetObjId);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId(relId);
        // Unnamed for the same reason as the elements: a label reaches the SWT text measurement.
        // The disclosure names a connection by label only when it HAS one, and by id always — the
        // assertions here read the id.
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId(connId);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
    }

    private IDiagramModelArchimateObject findObject(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId()) && child instanceof IDiagramModelArchimateObject o) {
                return o;
            }
        }
        throw new AssertionError("no view object with id " + id);
    }

    // ==================== harness ====================

    private ArchiModelAccessorImpl newAccessor(StubEditorModelManager mgr, IArchimateModel target) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> target) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(mgr, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final Set<PropertyChangeListener> listeners = new LinkedHashSet<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
