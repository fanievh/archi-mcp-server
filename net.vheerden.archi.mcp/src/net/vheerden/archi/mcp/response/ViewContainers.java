package net.vheerden.archi.mcp.response;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;

/**
 * The one place above the model layer that decides what counts as a container on a view.
 *
 * <p>Two different Archi objects render as an arrangeable labelled box, and the group-layout family
 * positions both: a native view group, and an ArchiMate {@code Grouping} element. They reach this
 * layer through different doors — a native group arrives in {@link ViewContentsDto#groups()}, while
 * a {@code Grouping} arrives among the elements — so every report that talks about "groups" has to
 * put them back together, and three of them do: {@code format=tree}, {@code format=summary} and
 * {@code format=graph}.
 *
 * <p><b>Why this class exists rather than a type test at each site.</b> The model layer answers the
 * same question with an {@code instanceof} against the element's concept. Handlers and formatters
 * are forbidden to import EMF, so up here the DTO's type name is all there is to test — and a
 * string test copied to three call sites is three chances to diverge from the model layer and from
 * each other. There is exactly one copy, and {@code GroupingContainerTypeNameTest} pins it against
 * what the ArchiMate factory actually produces.
 *
 * <p><b>Placements, not concepts.</b> The counts here are of diagram objects. An element drawn on
 * one view twice is deduplicated in {@link ViewContentsDto#elements()} but appears twice in
 * {@link ViewContentsDto#visualMetadata()}, and the model layer's collection walks diagram objects
 * too — so counting placements is what keeps these reports equal to what the layout tools report.
 */
public final class ViewContainers {

    private ViewContainers() {}

    /**
     * The EMF class name of the ArchiMate element whose diagram objects are containers.
     * {@code ElementDto.type()} is {@code eClass().getName()}, which is where this value comes from.
     */
    private static final String GROUPING_ELEMENT_TYPE = "Grouping";

    /**
     * Whether an element of this type renders as a container the group-layout family arranges.
     *
     * <p>Deliberately narrow, mirroring the model-layer predicate: an {@code ApplicationComponent}
     * holding functions, or a {@code Node} holding nested nodes, is a host rather than a zone.
     * Those nest in a containment tree and accept {@code layout-within-group}, but
     * {@code arrange-groups} will not reposition them <em>by default</em>, so counting them as
     * groups would restate the defect this exists to prevent.
     *
     * <p>The default is what this count mirrors, and only the default. {@code arrange-groups} does
     * position such a container when the caller names its view-object id in {@code groupIds} — a
     * per-call opt-in that leaves the collection this predicate describes untouched. So the count
     * stays equal to what a {@code groupIds}-free call arranges, which is the equality the reports
     * built on it publish; a call that names a host arranges more than this counts, deliberately.
     *
     * @param elementType an {@link ElementDto#type()}; {@code null} is safe and yields {@code false}
     */
    public static boolean isContainerType(String elementType) {
        return GROUPING_ELEMENT_TYPE.equals(elementType);
    }

    /** Whether this element's diagram objects are containers. A {@code null} element is not. */
    public static boolean isContainer(ElementDto element) {
        return element != null && isContainerType(element.type());
    }

    /**
     * The ids of every element on the view whose placements are containers.
     *
     * <p>Keyed by model-element id so a caller holding only ids — a graph node, say, whose field
     * preset may have dropped {@code type} — can still ask the question.
     */
    public static Set<String> containerElementIds(ViewContentsDto contents) {
        Set<String> ids = new HashSet<>();
        if (contents == null || contents.elements() == null) {
            return ids;
        }
        for (ElementDto element : contents.elements()) {
            if (isContainer(element) && element.id() != null) {
                ids.add(element.id());
            }
        }
        return ids;
    }

    /**
     * Every container drawn on the view, of both kinds, counted per placement.
     *
     * <p>Equals the {@code totalGroups} that {@code format=tree} reports for the same view, which is
     * the point: a caller must not be able to change format and be told a different number of
     * groups exists.
     */
    public static int countContainers(ViewContentsDto contents) {
        if (contents == null) {
            return 0;
        }
        int total = contents.groups() != null ? contents.groups().size() : 0;
        if (contents.visualMetadata() == null) {
            return total;
        }
        Map<String, ElementDto> elementById = new HashMap<>();
        if (contents.elements() != null) {
            for (ElementDto element : contents.elements()) {
                elementById.put(element.id(), element);
            }
        }
        for (ViewNodeDto node : contents.visualMetadata()) {
            if (isContainer(elementById.get(node.elementId()))) {
                total++;
            }
        }
        return total;
    }
}
