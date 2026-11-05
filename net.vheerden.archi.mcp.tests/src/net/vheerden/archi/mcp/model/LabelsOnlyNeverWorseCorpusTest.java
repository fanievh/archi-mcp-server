package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.ViewFixture;

/**
 * {@code auto-route-connections mode: "labels-only"} never makes a view worse by the label optimizer's own measure,
 * on every reference view in the test data: the labels as they stand after a run score no higher than before it,
 * Label Offsets included ({@link LabelOptimizationPass#labelsAsTheyStandScore}). Where that score falls, the
 * assessor's {@code labelOverlapCount} does not rise, and a second run changes nothing.
 *
 * <p>Each view is loaded from its fixture with every label at Middle and no Label Offset, then run as stored, again,
 * and once more after a full route. Every mismatch is collected, so one failing view does not hide the rest.</p>
 *
 * <p>One view breaks the last two rules, as it did before Label Offsets were placements: on
 * {@code st-source-clone} the first run lowers the score but raises {@code labelOverlapCount} from 5 to 6 (the
 * optimizer and the assessor weigh the trade differently), and a second run still changes one Label Offset. It is
 * pinned as it is, so a change in either direction is seen.</p>
 */
public class LabelsOnlyNeverWorseCorpusTest {

    private static final String KNOWN = "st-source-clone-fixture";

    private static final List<String> VIEWS = List.of(
            "retail-bank-application-collaboration-fixture", "retail-bank-drifted-anchors-fixture",
            "app-architecture-view-fixture", "hh-source-clone-fixture", KNOWN,
            "v4-integration-architecture-oracle-fixture", "retail-bank-business-architecture-fixture",
            "estate-interaction-topology-typed-fixture",
            "meridian-a-typed-fixture", "meridian-b-typed-fixture", "meridian-c-typed-fixture",
            "meridian-d-typed-fixture", "meridian-e-typed-fixture", "meridian-f-typed-fixture",
            "meridian-g-typed-fixture", "meridian-h-typed-fixture", "meridian-i-typed-fixture",
            "meridian-j-typed-fixture", "meridian-k-typed-fixture");

    @Test
    public void labelsOnly_neverScoresWorse_andARerunChangesNothing() throws Exception {
        List<String> mismatches = new ArrayList<>();
        boolean knownRose = false;
        boolean knownRerunMoved = false;
        for (String view : VIEWS) {
            CapturedViewHarness.Ctx c = load(view);
            AutoRouteArmFixture.StubEditorModelManager manager = new AutoRouteArmFixture.StubEditorModelManager();
            manager.setModels(List.of(c.model));
            MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
            dispatcher.setApprovalModeProvider(() -> false);
            ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(manager, dispatcher);
            try {
                double before = score(c);
                int overlapsBefore = accessor.assessLayout(CapturedViewHarness.VIEW_ID).labelOverlapCount();
                labelsOnly(accessor);
                double after = score(c);
                int overlapsAfter = accessor.assessLayout(CapturedViewHarness.VIEW_ID).labelOverlapCount();
                if (after > before) {
                    mismatches.add(view + ": the score rose " + before + " → " + after);
                }
                if (after < before && overlapsAfter > overlapsBefore) {
                    if (view.equals(KNOWN)) {
                        knownRose = overlapsBefore == 5 && overlapsAfter == 6;
                    } else {
                        mismatches.add(view + ": the score fell but labelOverlapCount rose " + overlapsBefore
                                + " → " + overlapsAfter);
                    }
                }

                String placed = labels(c);
                labelsOnly(accessor);
                if (!placed.equals(labels(c))) {
                    if (view.equals(KNOWN)) {
                        knownRerunMoved = true;
                    } else {
                        mismatches.add(view + ": a second run changed a label");
                    }
                }

                accessor.autoRouteConnections("corpus", CapturedViewHarness.VIEW_ID, null, "orthogonal", false,
                        false, 0, 0, null);
                double routed = score(c);
                labelsOnly(accessor);
                if (score(c) > routed) {
                    mismatches.add(view + ": after a full route the score rose " + routed + " → " + score(c));
                }
            } finally {
                dispatcher.endCall();
                accessor.dispose();
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue("on " + KNOWN + " the count still rises 5 → 6 where the score falls", knownRose);
        assertTrue("on " + KNOWN + " a second run still changes a label", knownRerunMoved);
    }

    private static void labelsOnly(ArchiModelAccessorImpl accessor) {
        accessor.autoRouteConnections("corpus", CapturedViewHarness.VIEW_ID, null, "orthogonal", false, false, 0, 0,
                "labels-only");
    }

    private static double score(CapturedViewHarness.Ctx c) {
        Double score = LabelOptimizationPass.labelsAsTheyStandScore(c.view, null);
        return score == null ? 0.0 : score;
    }

    /** Every connection's text position and Label Offset, in a fixed order. */
    private static String labels(CapturedViewHarness.Ctx c) {
        StringBuilder sb = new StringBuilder();
        for (String id : c.connectionIds) {
            IDiagramModelConnection conn = find(c, id);
            sb.append(id).append('=').append(conn.getTextPosition()).append('/')
                    .append(RelativePositionFeature.get(conn)).append(';');
        }
        return sb.toString();
    }

    /** The fixture's view with its stored bendpoints and labels, every label at Middle with no Label Offset. */
    private static CapturedViewHarness.Ctx load(String view) throws Exception {
        ViewFixture fixture = ViewFixture.load("testdata/" + view + ".json");
        CapturedViewHarness.Ctx c = CapturedViewHarness.fromFixture(fixture);
        for (ViewFixture.FixtureConnection fc : fixture.getConnections()) {
            IDiagramModelArchimateConnection conn = (IDiagramModelArchimateConnection) find(c, fc.id());
            if (fc.label() != null && !fc.label().isEmpty()) {
                conn.getArchimateRelationship().setName(fc.label());
            }
            conn.setTextPosition(1);
            for (ViewFixture.FixtureBendpoint bp : fc.bendpoints()) {
                IDiagramModelBendpoint b = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
                b.setStartX(bp.startX());
                b.setStartY(bp.startY());
                b.setEndX(bp.endX());
                b.setEndY(bp.endY());
                conn.getBendpoints().add(b);
            }
        }
        return c;
    }

    private static IDiagramModelConnection find(CapturedViewHarness.Ctx c, String id) {
        for (IDiagramModelObject o : c.objects.values()) {
            for (IDiagramModelConnection conn : o.getSourceConnections()) {
                if (conn.getId().equals(id)) {
                    return conn;
                }
            }
        }
        throw new AssertionError("no connection " + id);
    }
}
