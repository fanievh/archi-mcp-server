package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;

/**
 * Every route {@code auto-route-connections} writes is orthogonal and stays out of its own source
 * and target — over every captured view in {@code testdata}, routed end to end through the
 * accessor (the full pipeline under best-of-K, then the write path), and read back as stored.
 *
 * <p>Two rules, both on the segments between bendpoints (the two terminal segments are drawn
 * from the element centre by the anchor and are not the router's to keep straight):</p>
 * <ol>
 *   <li>each segment is axis-aligned, exactly — the router writes integers, so no tolerance;</li>
 *   <li>no segment enters the strict interior of the connection's own source or target. The
 *       terminal bendpoints sit on margin lines outside the faces, so a legitimate route never
 *       does; the per-connection obstacle list leaves both ends out, so nothing else checks it.</li>
 * </ol>
 *
 * <p>Every connection the call writes is checked, failed and force-written ones included — stricter
 * than routed-only, and green on the corpus. Where one end is nested inside the other, the route
 * must run inside the container to reach the child, so only the nested end's interior is checked.
 * The views are routed as {@link CapturedViewHarness} routes them: orthogonal, no grid snap.</p>
 *
 * <p>Every violation in every view is collected before the test fails, so one bad route cannot
 * hide the rest; a view that routes nothing, or whose written routes have no segment to check, is
 * a violation too, so the check cannot pass on a view it never saw.</p>
 */
public class AutoRouteOrthogonalRouteInvariantTest {

    /** The captured view that first showed a slanted segment written through its own source. */
    private static final String SLANTED_TRUNK_FIXTURE = "estate-interaction-topology-typed-fixture.json";

    @Test
    public void everyWrittenRoute_shouldBeOrthogonalAndClearOfItsOwnEnds_onEveryCapturedView()
            throws IOException {
        List<Path> fixtures = capturedViews();
        assertTrue("the corpus is found: " + fixtures, fixtures.size() >= 20);
        assertTrue("the view that showed the defect is in the corpus",
                fixtures.stream().anyMatch(p -> p.getFileName().toString().equals(SLANTED_TRUNK_FIXTURE)));

        List<String> violations = new ArrayList<>();
        for (Path path : fixtures) {
            ViewFixture fx = ViewFixture.load(path.toString());
            if (fx.getConnections().isEmpty()) {
                continue;
            }
            String name = path.getFileName().toString();
            List<ViewConnectionDto> written = CapturedViewHarness.route(
                    CapturedViewHarness.fromFixture(fx), (dto, accessor) -> read(dto, accessor, name, violations));
            int segments = 0;
            for (ViewConnectionDto conn : written) {
                segments += check(name, fx, conn, violations);
            }
            if (segments == 0) {
                violations.add(name + ": no written segment between bendpoints to check");
            }
        }
        if (!violations.isEmpty()) {
            fail(violations.size() + " written segment(s) break the route invariant:\n"
                    + String.join("\n", violations));
        }
    }

    private static List<ViewConnectionDto> read(AutoRouteResultDto dto, ArchiModelAccessorImpl accessor,
            String name, List<String> violations) {
        if (dto.connectionsRouted() == 0) {
            violations.add(name + ": the call routed no connection");
        }
        ViewContentsDto contents = accessor.getViewContents(CapturedViewHarness.VIEW_ID).orElseThrow();
        return contents.connections();
    }

    /** Adds each violation of {@code conn} to {@code out}; returns the number of segments checked. */
    private static int check(String name, ViewFixture fx, ViewConnectionDto conn, List<String> out) {
        List<AbsoluteBendpointDto> bps = conn.absoluteBendpoints();
        if (bps == null) {
            return 0;
        }
        ViewFixture.FixtureConnection fc = fx.getConnections().stream()
                .filter(c -> c.id().equals(conn.viewConnectionId())).findFirst().orElseThrow();
        ViewFixture.FixtureElement src = fx.getElementById(fc.sourceId());
        ViewFixture.FixtureElement tgt = fx.getElementById(fc.targetId());
        List<ViewFixture.FixtureElement> ends = new ArrayList<>();
        if (!contains(src, tgt)) {
            ends.add(src);
        }
        if (tgt != src && !contains(tgt, src)) {
            ends.add(tgt);
        }
        for (int i = 0; i + 1 < bps.size(); i++) {
            AbsoluteBendpointDto a = bps.get(i);
            AbsoluteBendpointDto b = bps.get(i + 1);
            String where = name + " " + conn.viewConnectionId() + " segment " + i + " " + bps;
            if (a.x() != b.x() && a.y() != b.y()) {
                out.add(where + ": not axis-aligned");
                continue;
            }
            for (ViewFixture.FixtureElement e : ends) {
                if (Math.max(a.x(), b.x()) > e.x() && Math.min(a.x(), b.x()) < e.x() + e.w()
                        && Math.max(a.y(), b.y()) > e.y() && Math.min(a.y(), b.y()) < e.y() + e.h()) {
                    out.add(where + ": enters its own " + (e.id().equals(fc.sourceId()) ? "source" : "target")
                            + " " + e.id());
                }
            }
        }
        return Math.max(0, bps.size() - 1);
    }

    /** Whether {@code outer} encloses {@code inner} (a container and an element nested in it). */
    private static boolean contains(ViewFixture.FixtureElement outer, ViewFixture.FixtureElement inner) {
        return outer != inner && outer.x() <= inner.x() && outer.y() <= inner.y()
                && outer.x() + outer.w() >= inner.x() + inner.w()
                && outer.y() + outer.h() >= inner.y() + inner.h();
    }

    /** Every captured view fixture, from the test project root or the repository root. */
    private static List<Path> capturedViews() throws IOException {
        Path dir = Path.of("testdata");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("net.vheerden.archi.mcp.tests", "testdata");
        }
        assertTrue("testdata directory found from " + Path.of("").toAbsolutePath(), Files.isDirectory(dir));
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-fixture.json")).sorted().toList();
        }
    }
}
