package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Executable proof that the own-face parallel terminal class survives a rise in
 * {@code EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN}, by handing off from one detector to
 * another rather than going unreported.
 *
 * <p>The margin is raised from 1 to 4 so a terminal bendpoint survives a fractional Windows device
 * transform. That move takes an own-face parallel terminal run out of M4's 3px band
 * ({@code LayoutQualityAssessor.EDGE_COINCIDENCE_TOLERANCE_PX}), so M4's published count drops —
 * which raises the question of whether that tolerance must move with the margin.
 *
 * <p>It must not, and the reason rests on ONE claim that had only been derived from source, never
 * executed: that the class is still reported by {@code offFaceParallelTerminals}
 * ({@code OFF_FACE_MIN_STUB_PX = 8.0}, declared COVERAGE_CHECKED, no overlap floor). This class
 * executes that claim.
 *
 * <p><b>Geometry.</b> {@code src} is 100x59 at (400,50), so its BOTTOM face line is y=109 and its
 * x-extent is 400..500. Every arm places the terminal bendpoint {@code stub} px below that face
 * with a horizontal trunk running right — the own-face parallel hug. Only {@code stub} varies
 * between arms (single-variable), except {@link #overlapBelowM4Floor_isOffFaceOnly_atEveryMargin}
 * which varies only the trunk's start x to drop M4's parallel overlap below its 10px floor.
 *
 * <p><b>What each detector should do, and why the arms are where they are:</b>
 * <pre>
 *   stub | M4 (band 3.0, overlap floor 10) | offFace (band 8.0, no overlap floor)
 *   -----+--------------------------------+-------------------------------------
 *     1  | flags   (1 &lt;= 3)                | flags   (1 &lt; 8)   &lt;- DOUBLE REPORT (margin 1, not shipped)
 *     2  | flags   (2 &lt;= 3)                | flags   (2 &lt; 8)
 *     4  | silent  (4 &gt;  3)                | flags   (4 &lt; 8)   &lt;- HANDOFF (margin 4, the shipped value)
 *     7  | silent                          | flags   (7 &lt; 8)   &lt;- tight control below the cap
 *     8  | silent                          | SILENT  (8 &gt;= 8)  &lt;- THE CLIFF (margin 8, which the tripwire forbids)
 *    12  | silent                          | silent
 * </pre>
 *
 * <p>The 7-vs-8 pair is what convicts the {@code >=} in
 * {@code if (stub >= OFF_FACE_MIN_STUB_PX) return null;}. Without the 7 arm, an arm at 8 alone
 * could be silent because {@code inferTerminalSlot} failed to resolve the face rather than because
 * the stub gate fired, and the fixture would certify the wrong mechanism.
 */
public class TerminalFaceMarginCoverageHandoffTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    /** src BOTTOM face line = 50 + 59 = 109, x-extent 400..500. tgt is the vertical approach only. */
    private static List<AssessmentNode> nodes() {
        return List.of(node("src", 400, 50, 100, 59), node("tgt", 620, 300, 60, 50));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    /**
     * Own-face parallel terminal hug {@code stub} px below src's BOTTOM face, trunk from x=473
     * (M4 parallel overlap against src = 500-473 = 27px, comfortably over the 10px floor), then a
     * vertical approach into tgt so no other terminal metric is implicated.
     */
    private static AssessmentConnection hugAt(double stub) {
        double y = 109 + stub;
        return new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, y},
                        new double[]{650, y}, new double[]{650, 325}), "", 1);
    }

    private int m4(AssessmentConnection conn) {
        return assessor.countConnectionEdgeCoincidence(List.of(conn), nodes(), false).count();
    }

    private int offFace(AssessmentConnection conn) {
        return assessor.countOffFaceParallelTerminals(List.of(conn), nodes(), false).count();
    }

    // ================================================================
    // The handoff itself: both halves asserted at both ends.
    // ================================================================

    @Test
    public void atMargin1_theClassIsDoubleReported_byBothDetectors() {
        // The pre-change state. One physical defect, two published counts — this is the
        // redundancy the margin move removes, and the evidence for reading (a).
        AssessmentConnection conn = hugAt(1);
        assertEquals("M4 counts an own-face hug 1px off the face (1 <= 3, overlap 27 >= 10)",
                1, m4(conn));
        assertEquals("offFaceParallelTerminals counts the same hug (stub 1 < 8)",
                1, offFace(conn));
    }

    @Test
    public void atMargin4_m4GoesSilent_butTheClassIsStillReported() {
        // THE LOAD-BEARING ASSERTION. If the second half fails, the class IS lost at margin 4,
        // the false floor is real, and holding EDGE_COINCIDENCE_TOLERANCE_PX at 3.0 must be
        // reconsidered.
        AssessmentConnection conn = hugAt(4);
        assertEquals("M4 no longer counts it: 4 > EDGE_COINCIDENCE_TOLERANCE_PX",
                0, m4(conn));
        assertEquals("offFaceParallelTerminals still counts it: stub 4 < OFF_FACE_MIN_STUB_PX",
                1, offFace(conn));
    }

    @Test
    public void theOffFaceCountDoesNotMoveBetweenMargin1And4() {
        // "The count does not move" is a two-ended claim; visit both ends explicitly rather than
        // inferring the pair from two separate single-ended tests.
        assertEquals("off-face count is invariant across the margin change",
                offFace(hugAt(1)), offFace(hugAt(4)));
        assertEquals("...and it is non-zero at both ends, so the invariance is not two zeroes",
                1, offFace(hugAt(1)));
    }

    // ================================================================
    // The cliff: margin 8 is the story's own fallback value.
    // ================================================================

    @Test
    public void atStub7_justBelowTheCap_theCheckedDetectorStillFires() {
        // Control for the arm below. Proves a silent result at 8 is the stub gate firing, not
        // inferTerminalSlot failing to resolve the face at that distance.
        assertEquals("stub 7 < 8 still reports", 1, offFace(hugAt(7)));
    }

    @Test
    public void atStub8_atTheCap_bothDetectorsGoSilent() {
        // The false floor on a COVERAGE_CHECKED metric. The gate is
        // 'if (stub >= OFF_FACE_MIN_STUB_PX) return null', so EQUALITY is already silent — which
        // is why the tripwire below must be strict '<', not '<='.
        AssessmentConnection conn = hugAt(8);
        assertEquals("M4 is silent at 8 (8 > 3)", 0, m4(conn));
        assertEquals("offFaceParallelTerminals is silent AT the cap (8 >= 8)", 0, offFace(conn));
    }

    @Test
    public void pastTheCap_bothDetectorsRemainSilent() {
        AssessmentConnection conn = hugAt(12);
        assertEquals(0, m4(conn));
        assertEquals(0, offFace(conn));
    }

    // ================================================================
    // Population coverage — the classes M4 and the repairer split differently.
    // ================================================================

    @Test
    public void nearFaceClass_isCoveredByBothDetectors_atMargin4() {
        // A run 2px off the face while the construction margin is 4 (a nudge, not a placement).
        // M4 covers it at 3.0 and the off-face detector covers it too — so raising the margin
        // does not strand this class.
        AssessmentConnection conn = hugAt(2);
        assertEquals("M4 still covers a genuinely near-face run (2 <= 3)", 1, m4(conn));
        assertEquals("and so does the off-face detector", 1, offFace(conn));
    }

    @Test
    public void overlapBelowM4Floor_isOffFaceOnly_atEveryMargin() {
        // Trunk starts at x=495, so M4's parallel overlap against src is 500-495 = 5px, under its
        // 10px floor. M4 never counted this class at ANY margin; the off-face detector has no
        // overlap floor and does. This is the population asymmetry that makes "the two detectors
        // are brought into agreement" a false statement, and it confirms the coverage claim in
        // TerminalEgressClearancePass:575-585.
        AssessmentConnection shortRun = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{495, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        assertEquals("M4 cannot see a sub-10px-overlap own-face run even at stub 1",
                0, m4(shortRun));
        assertEquals("the off-face detector reports it — no overlap floor",
                1, offFace(shortRun));
    }

    // ================================================================
    // The tripwire that keeps the checked off-face detector from going blind.
    // ================================================================

    @Test
    public void terminalFaceMarginMustStayBelowTheOffFaceStubFloor() {
        // TERMINAL_FACE_MARGIN feeds describeOffFaceParallelTerminal's stub directly. At
        // margin >= OFF_FACE_MIN_STUB_PX the checked off-face metric reports NOTHING on any
        // by-construction terminal, while still declaring COVERAGE_CHECKED — see
        // atStub8_atTheCap_bothDetectorsGoSilent for the executed proof of that consequence.
        assertTrue("TERMINAL_FACE_MARGIN ("
                        + net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator
                                .TERMINAL_FACE_MARGIN
                        + ") reached OFF_FACE_MIN_STUB_PX (" + LayoutQualityAssessor.OFF_FACE_MIN_STUB_PX
                        + "). offFaceParallelTerminals is now silent on every routed terminal while "
                        + "still declared COVERAGE_CHECKED. Rule on OFF_FACE_MIN_STUB_PX before "
                        + "shipping this margin.",
                net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN
                        < LayoutQualityAssessor.OFF_FACE_MIN_STUB_PX);
    }
}
