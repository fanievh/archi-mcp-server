package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Arm;
import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Ctx;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * {@code auto-route-connections} does not have one dispatch split. It has two, and three routes
 * into the note-crossing emitter.
 *
 * <p>{@code runTerminalsOnly} carries its own approval gate, its own {@code dispatchOrQueue} and
 * its own returns, and it emits the note-crossing disclosure from inside its own command builder. A
 * fix applied at the full-mode split alone leaves {@code mode: "terminals-only"} saying the routes
 * were applied while the response beside it says nothing was. {@code strategy: "clear"} is a third
 * route into the same emitter and is the easiest of the three to overlook, because clearing a route
 * reads like not applying one — which is backwards: with no pathfinder left to steer the line, it
 * goes straight through whatever sits between the endpoints, and {@code assess-layout} scores that
 * exactly as it scores a routed path.</p>
 *
 * <p>Every case here drives the REAL accessor. A second surface of a guard can be silently inert,
 * so these are not re-checks of the emitter with different arguments — they are checks that each
 * surface actually reaches it with the arm the call is on.</p>
 */
public class AutoRouteSplitCoverageTest {

    @Test
    public void fullMode_carriesAnArmAppropriateNoteCrossing_onAllThreeArms() {
        for (Arm arm : Arm.values()) {
            assertArmAppropriate(arm, "full mode",
                    noteCrossing(AutoRouteArmFixture.route(
                            AutoRouteArmFixture.oneNoteInCorridor(), arm)));
        }
    }

    @Test
    public void terminalsOnly_carriesAnArmAppropriateNoteCrossing_onAllThreeArms() {
        for (Arm arm : Arm.values()) {
            Ctx c = AutoRouteArmFixture.diagonalTerminalsThroughANote();
            AutoRouteResultDto dto = AutoRouteArmFixture.routeTerminalsOnly(c, arm);

            // PRECONDITION: terminals-only is a no-op on a connection whose terminals are already
            // orthogonal, and a call that applied nothing correctly discloses nothing. Without a
            // route to rewrite this test proves only that a no-op is quiet.
            assertEquals(arm + ": the fixture must actually give terminals-only work to do",
                    1, dto.connectionsRouted());
            assertArmAppropriate(arm, "terminals-only", noteCrossing(dto));
        }
    }

    @Test
    public void clearStrategy_carriesAnArmAppropriateNoteCrossing_onAllThreeArms() {
        for (Arm arm : Arm.values()) {
            assertArmAppropriate(arm, "strategy 'clear'",
                    noteCrossing(AutoRouteArmFixture.routeCleared(
                            AutoRouteArmFixture.oneNoteInCorridor(), arm)));
        }
    }

    @Test
    public void terminalsOnly_carriesAnArmAppropriateConnectionNotFound_onAllThreeArms() {
        // The not-found emitter fires above the split, so this is the case that would go unnoticed
        // if the arm were resolved at the RETURN instead of at the emit: the terminals-only return
        // is a different one, and a return-site fix reaches only whichever it was written against.
        for (Arm arm : Arm.values()) {
            AutoRouteResultDto dto = AutoRouteArmFixture.routeIds(
                    AutoRouteArmFixture.oneNoteInCorridor(), arm, "terminals-only",
                    List.of("conn-a", "no-such-connection"));

            StructuredWarningDto warning = AutoRouteArmFixture.structured(dto,
                    StructuredWarningCodes.CONNECTION_NOT_FOUND);
            assertNotNull(arm + ": terminals-only must still report the missing id", warning);
            assertEquals(arm + ": and carry it machine-readably",
                    List.of("no-such-connection"), warning.remediationViolatorIds());
            assertArmAppropriate(arm, "terminals-only not-found", warning);
        }
    }

    @Test
    public void fullMode_carriesAnArmAppropriateConnectionNotFound_onAllThreeArms() {
        for (Arm arm : Arm.values()) {
            AutoRouteResultDto dto = AutoRouteArmFixture.routeIds(
                    AutoRouteArmFixture.oneNoteInCorridor(), arm, null,
                    List.of("conn-a", "no-such-connection"));

            StructuredWarningDto warning = AutoRouteArmFixture.structured(dto,
                    StructuredWarningCodes.CONNECTION_NOT_FOUND);
            assertNotNull(arm + ": full mode must report the missing id", warning);
            assertArmAppropriate(arm, "full-mode not-found", warning);
        }
    }

    private static StructuredWarningDto noteCrossing(AutoRouteResultDto dto) {
        StructuredWarningDto warning = AutoRouteArmFixture.structured(dto,
                StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE);
        assertNotNull("the fixture must actually cross the note", warning);
        return warning;
    }

    /**
     * Asserts the disclosure describes the arm the call was on, and asserts it on BOTH sinks of the
     * one response — an applied claim surviving in {@code warnings} is the same lie one field
     * along.
     */
    private static void assertArmAppropriate(Arm arm, String surface, StructuredWarningDto warning) {
        String message = warning.message();
        switch (arm) {
            case APPLIED -> assertTrue(surface + " on the applied arm must say so plainly:\n"
                    + message, !message.contains("Nothing has been applied"));
            case QUEUED -> assertTrue(surface + " queued must say nothing has been applied:\n"
                    + message, message.contains("Nothing has been applied")
                    && message.contains("queued in the open batch"));
            case AWAITING_APPROVAL -> assertTrue(surface
                    + " awaiting approval must say nothing has been applied:\n" + message,
                    message.contains("Nothing has been applied")
                    && message.contains("waiting on the human's decision"));
        }
    }
}
