package net.vheerden.archi.mcp.model;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.emf.ecore.EClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.viewpoints.IViewpoint;
import com.archimatetool.model.viewpoints.ViewpointManager;

/**
 * Which objects on a view its viewpoint does not allow. Archi draws each of them ghosted, and
 * nothing else on this server says so: {@code add-to-view} places an element whatever the view's
 * viewpoint, and {@code assess-layout} rated such a view on geometry alone.
 *
 * <p><strong>Archi's rule, mirrored.</strong> An element is allowed when the viewpoint lists its
 * type, or lists no element types at all; a Junction and a Grouping are always allowed. A
 * relationship is allowed when listed, or when the viewpoint lists none (none of Archi's do). A
 * connection is allowed when its relationship is and both its ends are, so a connection to a ghosted
 * element is ghosted with it. Notes, groups, images and view references are always allowed.</p>
 *
 * <p><strong>Where the definitions come from.</strong> Archi's viewpoint registry, when it can
 * initialise. Outside OSGi (the test lanes) it cannot, so the definitions are read from the
 * {@code viewpoints.xml} that registry reads, with the type groups ({@code $ApplicationElements$}
 * and the rest) expanded from the same model lists Archi expands them from. When neither can be
 * read the verdict is {@code not-checked}, never a guess.</p>
 *
 * <p>Detection only: the count rides on no rating band.</p>
 */
public final class ViewpointConformance {

    /**
     * The coverage level for the {@code viewpointConformance} dimension and the ids found. The ids
     * are empty, and the count null, whenever the coverage is not {@code checked}.
     */
    public record Verdict(String coverage, List<String> nonConformingIds) {

        /** The published count: null when nothing was examined. */
        public Integer count() {
            return LayoutQualityAssessor.COVERAGE_CHECKED.equals(coverage)
                    ? nonConformingIds.size() : null;
        }
    }

    private static final Verdict NO_VIEWPOINT =
            new Verdict(LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, List.of());
    private static final Verdict UNREADABLE =
            new Verdict(LayoutQualityAssessor.COVERAGE_NOT_CHECKED, List.of());

    /** Viewpoint id to its allowed-concept predicate, read from the definitions file once. */
    private static volatile Map<String, Predicate<EClass>> fromFile;

    private static final Logger logger = LoggerFactory.getLogger(ViewpointConformance.class);

    private ViewpointConformance() {
    }

    /**
     * The verdict for a view as the model holds it now. Never throws: a failure anywhere in the
     * lookup or the walk is logged and answered {@code not-checked}, so this count can never take
     * the assessment it rides on down with it.
     */
    public static Verdict of(IArchimateDiagramModel view) {
        try {
            return measure(view);
        } catch (RuntimeException | LinkageError e) {
            logger.warn("Viewpoint conformance not checked: {}", e.toString());
            return UNREADABLE;
        }
    }

    private static Verdict measure(IArchimateDiagramModel view) {
        String id = view == null ? null : view.getViewpoint();
        if (id == null || id.isEmpty()) {
            return NO_VIEWPOINT;
        }
        Predicate<EClass> allowed;
        try {
            allowed = registryPredicate(id);
        } catch (LinkageError notInitialisable) {
            allowed = filePredicate(id);
            if (allowed == UNREADABLE_PREDICATE) {
                return UNREADABLE;
            }
        }
        if (allowed == null) {
            // An id Archi does not define is drawn as no viewpoint: everything is allowed.
            return NO_VIEWPOINT;
        }
        return new Verdict(LayoutQualityAssessor.COVERAGE_CHECKED, nonConforming(view, allowed));
    }

    /** The ids of the objects and connections {@code allowed} rejects, objects first. */
    static List<String> nonConforming(IArchimateDiagramModel view, Predicate<EClass> allowed) {
        List<IDiagramModelObject> objects = new ArrayList<>();
        collectObjects(view, objects);
        Set<IDiagramModelArchimateConnection> connections = new LinkedHashSet<>();
        for (IDiagramModelObject object : objects) {
            collectConnections(object, connections);
        }
        List<String> ids = new ArrayList<>();
        Map<IDiagramModelArchimateComponent, Boolean> memo = new HashMap<>();
        for (IDiagramModelObject object : objects) {
            if (object instanceof IDiagramModelArchimateComponent component
                    && !isAllowed(component, allowed, memo)) {
                ids.add(object.getId());
            }
        }
        for (IDiagramModelArchimateConnection connection : connections) {
            if (!isAllowed(connection, allowed, memo)) {
                ids.add(connection.getId());
            }
        }
        return List.copyOf(ids);
    }

    private static boolean isAllowed(IDiagramModelArchimateComponent component,
            Predicate<EClass> allowed, Map<IDiagramModelArchimateComponent, Boolean> memo) {
        Boolean known = memo.get(component);
        if (known != null) {
            return known;
        }
        memo.put(component, Boolean.TRUE); // a cycle of connections is judged on its own types
        boolean result = component.getArchimateConcept() == null
                || allowed.test(component.getArchimateConcept().eClass());
        if (result && component instanceof IDiagramModelArchimateConnection connection) {
            result = endAllowed(connection.getSource(), allowed, memo)
                    && endAllowed(connection.getTarget(), allowed, memo);
        }
        memo.put(component, result);
        return result;
    }

    private static boolean endAllowed(IConnectable end, Predicate<EClass> allowed,
            Map<IDiagramModelArchimateComponent, Boolean> memo) {
        return !(end instanceof IDiagramModelArchimateComponent component)
                || isAllowed(component, allowed, memo);
    }

    private static void collectObjects(IDiagramModelContainer container, List<IDiagramModelObject> into) {
        for (IDiagramModelObject child : container.getChildren()) {
            into.add(child);
            if (child instanceof IDiagramModelContainer nested) {
                collectObjects(nested, into);
            }
        }
    }

    private static void collectConnections(IConnectable source,
            Set<IDiagramModelArchimateConnection> into) {
        for (IDiagramModelConnection connection : source.getSourceConnections()) {
            if (connection instanceof IDiagramModelArchimateConnection archimate && into.add(archimate)) {
                collectConnections(archimate, into);
            } else if (!(connection instanceof IDiagramModelArchimateConnection)) {
                collectConnections(connection, into);
            }
        }
    }

    // ---- definitions --------------------------------------------------------------------------

    /** Archi's own predicate for {@code id}, or null when Archi defines no such viewpoint. */
    private static Predicate<EClass> registryPredicate(String id) {
        IViewpoint viewpoint = ViewpointManager.INSTANCE.getViewpoint(id);
        if (viewpoint == null || viewpoint == ViewpointManager.NONE_VIEWPOINT
                || viewpoint.getID() == null || viewpoint.getID().isEmpty()) {
            return null;
        }
        return viewpoint::isAllowedConcept;
    }

    /** Marks a definitions file that could not be read, as distinct from an unknown id (null). */
    private static final Predicate<EClass> UNREADABLE_PREDICATE = e -> true;

    private static Predicate<EClass> filePredicate(String id) {
        Map<String, Predicate<EClass>> definitions = fromFile;
        if (definitions == null) {
            try {
                definitions = readDefinitionsFile();
            } catch (Exception | LinkageError unreadable) {
                return UNREADABLE_PREDICATE;
            }
            fromFile = definitions;
        }
        return definitions.get(id);
    }

    private static Map<String, Predicate<EClass>> readDefinitionsFile() throws Exception {
        File jar = new File(ViewpointManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File file = new File(jar.isDirectory() ? jar : jar.getParentFile(), "model/viewpoints.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList viewpoints = factory.newDocumentBuilder().parse(file).getElementsByTagName("viewpoint");
        Map<String, EClass[]> groups = typeGroups();
        IArchimatePackage pkg = IArchimatePackage.eINSTANCE;
        Map<String, Predicate<EClass>> definitions = new LinkedHashMap<>();
        for (int i = 0; i < viewpoints.getLength(); i++) {
            Element viewpoint = (Element) viewpoints.item(i);
            Set<EClass> elements = new HashSet<>();
            Set<EClass> relations = new HashSet<>();
            NodeList concepts = viewpoint.getElementsByTagName("concept");
            for (int c = 0; c < concepts.getLength(); c++) {
                String name = concepts.item(c).getTextContent().trim();
                EClass[] group = groups.get(name);
                List<EClass> types = new ArrayList<>();
                if (group != null) {
                    types.addAll(List.of(group));
                } else if (pkg.getEClassifier(name) instanceof EClass eClass) {
                    types.add(eClass);
                } else {
                    throw new IllegalStateException("Unknown concept in viewpoint definitions: " + name);
                }
                for (EClass type : types) {
                    (pkg.getArchimateRelationship().isSuperTypeOf(type) ? relations : elements).add(type);
                }
            }
            definitions.put(viewpoint.getAttribute("id"), concept -> allows(elements, relations, concept));
        }
        return Map.copyOf(definitions);
    }

    /** Archi's {@code Viewpoint.isAllowedConcept}, over the lists read from the file. */
    static boolean allows(Set<EClass> elements, Set<EClass> relations, EClass concept) {
        IArchimatePackage pkg = IArchimatePackage.eINSTANCE;
        if (!pkg.getArchimateConcept().isSuperTypeOf(concept)) {
            return true;
        }
        if (pkg.getArchimateElement().isSuperTypeOf(concept) && elements.isEmpty()) {
            return true;
        }
        if (pkg.getArchimateRelationship().isSuperTypeOf(concept) && relations.isEmpty()) {
            return true;
        }
        return elements.contains(concept) || relations.contains(concept)
                || concept == pkg.getJunction() || concept == pkg.getGrouping();
    }

    /** The type groups the definitions file names, expanded as Archi's registry expands them. */
    private static Map<String, EClass[]> typeGroups() {
        IArchimatePackage pkg = IArchimatePackage.eINSTANCE;
        Map<String, EClass[]> groups = new HashMap<>();
        groups.put("$BusinessElements$", ArchimateModelUtils.getBusinessClasses());
        groups.put("$ApplicationElements$", ArchimateModelUtils.getApplicationClasses());
        groups.put("$TechnologyElements$", ArchimateModelUtils.getTechnologyClasses());
        groups.put("$PhysicalElements$", ArchimateModelUtils.getPhysicalClasses());
        groups.put("$StrategyElements$", ArchimateModelUtils.getStrategyClasses());
        groups.put("$MotivationElements$", ArchimateModelUtils.getMotivationClasses());
        groups.put("$ImplementationMigrationElements$",
                ArchimateModelUtils.getImplementationMigrationClasses());
        groups.put("$StructuralRelationships$", relationsUnder(pkg.getStructuralRelationship()));
        groups.put("$DependencyRelationships$", relationsUnder(pkg.getDependendencyRelationship()));
        groups.put("$DynamicRelationships$", relationsUnder(pkg.getDynamicRelationship()));
        groups.put("$OtherRelationships$", relationsUnder(pkg.getOtherRelationship()));
        return groups;
    }

    private static EClass[] relationsUnder(EClass kind) {
        List<EClass> types = new ArrayList<>();
        for (EClass relation : ArchimateModelUtils.getRelationsClasses()) {
            if (kind.isSuperTypeOf(relation)) {
                types.add(relation);
            }
        }
        return types.toArray(new EClass[0]);
    }
}
