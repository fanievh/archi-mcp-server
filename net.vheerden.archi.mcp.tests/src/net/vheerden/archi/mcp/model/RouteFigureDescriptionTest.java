package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * The served tool descriptions say what {@code crossingsAfter} and {@code labelsOptimized} count, and which lever
 * removes a redundant bendpoint. Each claim is read off the live registration and pinned whole, so a return to the
 * old wording (re-routing as the remedy for every point, a terminals-only pass that drives the count to zero) fails.
 */
public class RouteFigureDescriptionTest {

    @Test
    public void autoRouteDescription_saysWhatItsFiguresCount() {
        String text = served("auto-route-connections");
        assertTrue(text, text.contains("crossingsAfter counts the routes as the view stores them, so the next call on"
                + " an untouched view reads the same figure as its crossingsBefore. labelsOptimized counts the labels"
                + " the call writes (a text position or a Label Offset); a label moves only with a route the call"
                + " writes, and never while the label policy hides it. "));
    }

    @Test
    public void autoRouteDescription_doesNotPromiseThatTerminalsOnlyClearsTheCount() {
        String text = served("auto-route-connections");
        assertTrue(text, text.contains("It does not remove a first or last bendpoint that sits off its element's face"
                + " in line with that element's centre, which connectionRedundantBendpointCount can still report:"
                + " remove that one with update-view-connection. "));
        assertFalse(text, text.contains("toward zero"));
    }

    @Test
    public void assessLayoutDescription_namesTheLeverThatRemovesEveryReportedPoint() {
        String text = served("assess-layout");
        assertTrue(text, text.contains("The reported points are genuinely removable: remove each with"
                + " update-view-connection by writing the connection's bendpoints without it (the drawn route does"
                + " not change); that works for every reported point. Re-running auto-route-connections removes an"
                + " interior one, but not a first or last bendpoint off its element's face and in line with that"
                + " element's centre: the router's collinear sweep compares bendpoints with their neighbouring"
                + " bendpoints only, never with the element centres, so that point survives every routing mode,"
                + " full or terminals-only."));
        assertFalse(text, text.contains("straighten the route or re-run auto-route-connections"));
    }

    private static String served(String tool) {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(new BaseTestAccessor(), new ResponseFormatter(), registry, sessions);
        McpSchema.Tool served = registry.getToolSpecifications().stream()
                .filter(spec -> tool.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + tool))
                .tool();
        return served.description();
    }
}
