package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Test-only bridge to the package-private routing warning emitters.
 *
 * <p>Exists so a HANDLER-layer test can assert against the strings the MODEL layer actually
 * produces. The alternative — restating those strings in the handler test — is the configuration
 * that shipped the defect this bridge is used to guard: the prose half had full three-arm coverage
 * and the structured half had full per-code coverage, both green, while a single response
 * contradicted itself between them. Two suites can only cross-check each other if at least one of
 * them reads the other's real output rather than a copy of it.</p>
 *
 * <p>Deliberately NOT a production accessor. The emitters stay package-private; nothing in
 * {@code net.vheerden.archi.mcp} gains a wider surface because a test needed one.</p>
 */
public final class AutoRouteWarningsProbe {

    private AutoRouteWarningsProbe() {}

    /** The structured entry and the free text one emitter produced, exactly as it produced them. */
    public record Emitted(StructuredWarningDto structured, List<String> freeText) {}

    public static Emitted connectionsNotFound(DispatchArm arm) {
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitConnectionsNotFound(List.of("bogus-id"), arm, free, structured);
        return new Emitted(structured.get(0), free);
    }

    public static Emitted egressLiftLayoutBound(DispatchArm arm) {
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitEgressLiftLayoutBound(1, arm, free, structured);
        return new Emitted(structured.get(0), free);
    }

    public static Emitted netZeroNudge(DispatchArm arm) {
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "internal API GW", 0, 0)), arm, free,
                structured);
        return new Emitted(structured.get(0), free);
    }

    public static Emitted crossingsRegressed(DispatchArm arm) {
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        RoutingPipeline.appendCrossingWarnings(6, 17, 0, true, arm, free, structured);
        return new Emitted(structured.get(0), free);
    }

    public static Emitted connectionThroughNote(DispatchArm arm) {
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        Map<String, List<AbsoluteBendpointDto>> paths = new LinkedHashMap<>();
        paths.put("conn-1", List.of(new AbsoluteBendpointDto(300, 227)));
        AutoRouteWarnings.emitConnectionThroughNote(paths,
                List.of(new AssessmentConnection("conn-1", "src", "tgt",
                        List.of(new double[]{150, 227}, new double[]{300, 227},
                                new double[]{550, 227}), "flows to", 0)),
                List.of(node("src", 100, 200, 100, 55, false),
                        node("tgt", 500, 200, 100, 55, false),
                        node("obj-note-a", 280, 190, 60, 60, true)),
                arm, free, structured);
        return new Emitted(structured.get(0), free);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h,
            boolean isNote) {
        return new AssessmentNode(id, x, y, w, h, null, false, isNote, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
