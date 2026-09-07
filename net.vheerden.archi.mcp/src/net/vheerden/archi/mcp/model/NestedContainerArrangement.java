package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.NestedContainerDto;

/**
 * The zones {@code arrange-groups} arranges inside a host instead of on the canvas.
 *
 * <p>A container the arrangement predicate declines — a {@code Node} typing a cloud region, say —
 * can hold zones that no other zone contains. Those zones are top-level in the sense the family
 * cares about, and until now the tool refused the whole view rather than arrange them: a direct
 * children walk found no container at all and the call ended in "No top-level groups found", a
 * confidently false statement about a view holding two populated zones.
 *
 * <p><b>They cannot simply join the canvas arrangement.</b> A nested object's x/y are stored
 * relative to its immediate parent, so a position computed in canvas coordinates would place the
 * zone somewhere inside its host and reserve a canvas grid slot for a box that is not on the
 * canvas. Each host's zones are therefore arranged in that host's own space, from that host's own
 * content origin, with the spacing the call resolved — the same arrangement, computed in a
 * different frame.
 *
 * <p><b>A host is never grown to fit.</b> The parent-fit cascade grows native view groups; a host
 * that is an ArchiMate element is not one, so an arrangement that would not fit is declined for
 * that host rather than half-applied, and the zones stay where the caller left them. Overflow is
 * judged by {@link ParentFitCascade#childExceedsParentBounds}, the padded relative-coordinate test
 * the cascade itself uses, so "outside its parent" means here exactly what it means there.
 */
final class NestedContainerArrangement {

    private NestedContainerArrangement() {}

    /**
     * One host's arrangement: which zones, where they go, and whether they fit.
     *
     * @param host the container holding the zones, and the origin their positions are relative to
     * @param zones the outermost targets directly inside {@code host}, in the host's child order
     * @param positions the {@code [x, y]} each zone moves to, host-relative, index-aligned to
     *     {@code zones}; empty when {@link #fits} is false, because nothing is moved then
     * @param fits whether every zone lands inside the host. False declines the whole host: moving
     *     some of its zones and leaving the rest would produce a layout no caller asked for.
     */
    record HostPlan(IDiagramModelObject host, List<IDiagramModelObject> zones,
            List<int[]> positions, boolean fits) {}

    /**
     * The outermost targets that are NOT the view's own children, grouped by the container that
     * directly holds each.
     *
     * <p>Keyed by the immediate parent rather than by the topmost host, because that is the object
     * a nested position is measured against: a zone two containers deep is positioned relative to
     * the inner one. Insertion order follows the walk, so an arrangement is predictable from the
     * order the caller built the view in.</p>
     */
    static Map<IDiagramModelObject, List<IDiagramModelObject>> byHost(
            IDiagramModelContainer view) {
        Map<IDiagramModelObject, List<IDiagramModelObject>> byHost = new LinkedHashMap<>();
        for (IDiagramModelObject zone : TopLevelGroupTargets.collectOutermost(view)) {
            if (zone.eContainer() instanceof IDiagramModelObject host) {
                byHost.computeIfAbsent(host, h -> new ArrayList<>()).add(zone);
            }
        }
        return byHost;
    }

    /**
     * Every container this call arranges: the canvas ones plus the zones it will arrange inside
     * their hosts.
     *
     * <p>Decided WITHOUT reference to spacing, so it can be asked before a spacing default has
     * been resolved — which is exactly when it is needed, because the density-aware default is
     * chosen from how many of these containers there are and how many connections cross between
     * them. Asking only the canvas half there made a call that arranges two connected zones
     * resolve its spacing as though it were arranging fewer than two containers.</p>
     *
     * <p>A host that later turns out to be too small is still counted here. Whether the
     * arrangement FITS depends on the spacing this answer helps choose, so excluding a declined
     * host would make the question circular; and the containers are what the call is about
     * either way.</p>
     */
    static List<IDiagramModelObject> allArrangedContainers(IDiagramModelContainer view,
            List<IDiagramModelObject> canvasTargets, java.util.Set<String> restrictTo) {
        List<IDiagramModelObject> all = new ArrayList<>(canvasTargets);
        for (List<IDiagramModelObject> zones : byHost(view).values()) {
            for (IDiagramModelObject zone : zones) {
                if (restrictTo == null || restrictTo.contains(zone.getId())) {
                    all.add(zone);
                }
            }
        }
        return all;
    }

    /**
     * What this call would do inside each host, decided before anything is written.
     *
     * <p>Read entirely from the view as it stands, so the answer is as honest on a queued or
     * proposed call as on an applied one — the geometry it reports back afterwards is not.</p>
     *
     * @param arrangement the normalized arrangement, already resolved to a single axis or grid
     * @param spacing the gap this call resolved, shared with the canvas arrangement so the two
     *     halves of one call do not space differently
     * @param restrictTo the zone ids the caller named, or null when it named none. A restriction
     *     narrows each host's set the way {@code groupIds} narrows the canvas one, and a host left
     *     with nothing named produces no plan at all rather than an empty one.
     */
    static List<HostPlan> plan(IDiagramModelContainer view, String arrangement,
            Integer columns, int spacing, int padding, java.util.Set<String> restrictTo) {
        List<HostPlan> plans = new ArrayList<>();
        for (Map.Entry<IDiagramModelObject, List<IDiagramModelObject>> entry
                : byHost(view).entrySet()) {
            IDiagramModelObject host = entry.getKey();
            List<IDiagramModelObject> zones = entry.getValue();
            if (restrictTo != null) {
                List<IDiagramModelObject> named = new ArrayList<>();
                for (IDiagramModelObject zone : zones) {
                    if (restrictTo.contains(zone.getId())) {
                        named.add(zone);
                    }
                }
                zones = named;
            }
            if (zones.isEmpty()) {
                continue;
            }
            ContainerArrangement.Placement placement = ContainerArrangement.compute(
                    zones, arrangement, columns, spacing, List.of(),
                    ContainerArrangement.ORIGIN, ContainerArrangement.ORIGIN);
            IBounds hostBounds = host.getBounds();
            boolean fits = true;
            for (int i = 0; i < zones.size(); i++) {
                IBounds zoneBounds = zones.get(i).getBounds();
                int[] position = placement.positions().get(i);
                if (ParentFitCascade.childExceedsParentBounds(position[0], position[1],
                        zoneBounds.getWidth(), zoneBounds.getHeight(),
                        hostBounds.getWidth(), hostBounds.getHeight(), padding)) {
                    fits = false;
                    break;
                }
            }
            plans.add(new HostPlan(host, zones, fits ? placement.positions() : List.of(), fits));
        }
        return plans;
    }

    /**
     * The hosts whose zones this call evaluated and DECLINED for want of room, by id.
     *
     * <p>Kept apart from {@link #arrangedHostIds} because the two ask the caller for different
     * next actions: an arranged host needs nothing, and a declined one is fixed by enlarging it
     * and calling again. Collapsing them into "not arranged" would report a host this call
     * measured and rejected exactly like one that never held a zone.</p>
     */
    static java.util.Set<String> declinedHostIds(
            IDiagramModelContainer view, List<HostPlan> plans) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (HostPlan plan : plans) {
            if (!plan.fits() && !plan.zones().isEmpty()) {
                addViewLevelAncestor(view, plan.host(), ids);
            }
        }
        return ids;
    }

    /** The hosts whose zones these plans actually arranged, by id. */
    static java.util.Set<String> arrangedHostIds(
            IDiagramModelContainer view, List<HostPlan> plans) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (HostPlan plan : plans) {
            if (plan.fits() && !plan.zones().isEmpty()) {
                addViewLevelAncestor(view, plan.host(), ids);
            }
        }
        return ids;
    }

    /**
     * Records the DIRECT CHILD of the view that ultimately holds {@code host}.
     *
     * <p>A plan is keyed by the zone's immediate parent, because that is the origin its
     * coordinates are measured from. The skipped bucket, though, only ever lists the view's own
     * children — so with a host drawn inside another host, the plan names the inner one and the
     * entry the caller reads is the outer one. Looking the inner id up against that entry misses,
     * and the outer host is told it was "left where it is" with no mention that a container two
     * levels inside it just moved. Walking up to the id the bucket actually holds is what makes
     * the disclosure survive a second level of nesting.</p>
     */
    private static void addViewLevelAncestor(IDiagramModelContainer view,
            IDiagramModelObject host, java.util.Set<String> ids) {
        IDiagramModelObject current = host;
        while (current != null && current.eContainer() != view) {
            current = (current.eContainer() instanceof IDiagramModelObject parent) ? parent : null;
        }
        if (current != null) {
            ids.add(current.getId());
        }
    }

    /**
     * Where each arranged zone actually ended up, read back from the model.
     *
     * <p>Must be called <em>after</em> the arrangement is applied. Archi re-fits a container to its
     * children on write, so a computed rectangle is a claim about the request rather than a report
     * of the model, and an agent that cannot see the canvas has nothing to correct it with.</p>
     */
    static List<NestedContainerDto> effectiveGeometryOf(List<HostPlan> plans) {
        List<NestedContainerDto> arranged = new ArrayList<>();
        for (HostPlan plan : plans) {
            if (!plan.fits()) {
                continue;
            }
            for (IDiagramModelObject zone : plan.zones()) {
                IBounds b = zone.getBounds();
                String name = (zone.getName() != null && !zone.getName().isBlank())
                        ? zone.getName() : zone.getId();
                arranged.add(new NestedContainerDto(zone.getId(), name,
                        b.getX(), b.getY(), b.getWidth(), b.getHeight(), plan.host().getId()));
            }
        }
        return arranged;
    }
}
