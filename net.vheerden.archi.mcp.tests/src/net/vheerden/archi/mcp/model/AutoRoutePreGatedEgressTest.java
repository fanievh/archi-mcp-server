package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.model.CapturedViewHarness.Ctx;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * {@code auto-route-connections} must say so when its off-face lift pass was skipped wholesale by
 * the view-level pre-gate while connections still hug the faces they leave — end to end, through
 * the accessor, on a real captured view whose routes crowd their vertical runs below the gate.
 *
 * <p>The view is built from a captured fixture with every element, group, note and relationship
 * left UNNAMED: a label reaches SWT text measurement, which throws an {@code Error} on a
 * display-less runner (see {@code AutoRouteNoteCrossingDisclosureTest#addHost}). Routing reads
 * geometry only.</p>
 */
public class AutoRoutePreGatedEgressTest {

    private static final Pattern GAP = Pattern.compile("10th-percentile gap (\\d+\\.\\d)px, below the 8px floor");

    @Test
    public void shouldReportThePreGateSkipWithItsGapAndTheHuggingConnections_onACrowdedCapturedView()
            throws IOException {
        Ctx c = fromFixture("testdata/meridian-c-typed-fixture.json");

        AutoRouteResultDto dto = route(c);

        assertTrue("precondition: the call routed the view", dto.connectionsRouted() > 0);
        StructuredWarningDto w = warning(dto, StructuredWarningCodes.EGRESS_LIFT_PRE_GATED);
        assertNotNull("a pre-gated skip that leaves hugs must be reported: " + dto.structuredWarnings(), w);
        Matcher gap = GAP.matcher(w.message());
        assertTrue("the message gives the gap and the floor: " + w.message(), gap.find());
        assertTrue("the gap is below the floor", Double.parseDouble(gap.group(1)) < 8.0);
        assertFalse("the ids name connections still hugging", w.remediationViolatorIds().isEmpty());
        assertTrue("each is a connection on this view: " + w.remediationViolatorIds(),
                c.connectionIds.containsAll(w.remediationViolatorIds()));
        assertEquals("each is named once", w.remediationViolatorIds().size(),
                new LinkedHashSet<>(w.remediationViolatorIds()).size());
        assertTrue("the count in the message is the number of ids: " + w.message(),
                w.message().startsWith(w.remediationViolatorIds().size() + " connection(s) still hug"));
        assertEquals("apply-spacing-recommendations", w.remediationTool());
        assertTrue("mirrored into the free-text warnings", dto.warnings().contains(w.message()));
        assertNull("a skip rolls nothing back, so never both codes",
                warning(dto, StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND));
    }

    @Test
    public void shouldEmitOnlyTheLayoutBoundCode_whenThePassRanAndRolledALiftBack() throws IOException {
        // The sibling control on a captured view where the gate passes and the pass declines lifts.
        AutoRouteResultDto dto = route(fromFixture("testdata/meridian-g-typed-fixture.json"));

        assertNotNull("precondition: this view's pass ran and rolled a lift back",
                warning(dto, StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND));
        assertNull(warning(dto, StructuredWarningCodes.EGRESS_LIFT_PRE_GATED));
    }

    @Test
    public void shouldStaySilent_onASparseViewTheGateCannotFireOn() {
        Ctx c = CapturedViewHarness.newView();
        CapturedViewHarness.addBox(c, "a", "BusinessActor", null, 0, 200, 120, 55);
        CapturedViewHarness.addBox(c, "b", "BusinessActor", null, 600, 400, 120, 55);
        CapturedViewHarness.connect(c, "conn-a", "obj-a", "obj-b");

        AutoRouteResultDto dto = route(c);

        assertTrue("precondition: the call routed the view", dto.connectionsRouted() > 0);
        assertNull(warning(dto, StructuredWarningCodes.EGRESS_LIFT_PRE_GATED));
    }

    // ==================== reading ====================

    private static StructuredWarningDto warning(AutoRouteResultDto dto, String code) {
        if (dto.structuredWarnings() == null) {
            return null;
        }
        for (StructuredWarningDto w : dto.structuredWarnings()) {
            if (code.equals(w.code())) {
                return w;
            }
        }
        return null;
    }

    // ==================== fixture plumbing ====================

    private static Ctx fromFixture(String path) throws IOException {
        return CapturedViewHarness.fromFixture(path);
    }

    private static AutoRouteResultDto route(Ctx c) {
        return CapturedViewHarness.route(c);
    }
}
