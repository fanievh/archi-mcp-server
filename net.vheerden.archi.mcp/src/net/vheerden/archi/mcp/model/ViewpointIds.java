package net.vheerden.archi.mcp.model;

import java.io.File;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.archimatetool.model.viewpoints.IViewpoint;
import com.archimatetool.model.viewpoints.ViewpointManager;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The viewpoint ids {@code create-view} and {@code update-view} accept: the empty string (no viewpoint) and
 * every id Archi's {@link ViewpointManager} defines, matched exactly. Archi's own viewpoint menu can only
 * pick one of those, so storing any other id would be more forgiving than Archi; such a view renders with no
 * viewpoint. A view that already stores an unknown id is not touched: only a value a caller sends is checked.
 *
 * <p>The ids come from {@code ViewpointManager.INSTANCE.getAllViewpoints()}. That registry loads its
 * definitions through the OSGi platform, so it cannot initialise in a plain JVM (the headless and display
 * test lanes); there, and only there, the ids are read from the same definitions file the registry reads,
 * {@code model/viewpoints.xml} in the bundle that holds it. Either way the list is Archi's, never a copy.</p>
 */
public final class ViewpointIds {

    private static volatile SortedSet<String> known;

    private ViewpointIds() {
    }

    /**
     * Refuses a viewpoint id Archi does not define. Does nothing for {@code null} (not sent) or {@code ""}
     * (no viewpoint, or clear).
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the value and listing the valid ids
     */
    public static void requireKnown(String viewpoint) {
        if (viewpoint == null || viewpoint.isEmpty() || known().contains(viewpoint)) {
            return;
        }
        throw new ModelAccessException(
                "Unknown viewpoint " + CallerText.quoted(viewpoint) + ": Archi defines no viewpoint with that id, "
                        + "so it is refused rather than stored. Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Use one of: " + String.join(", ", known()) + "; or \"\" for no viewpoint.", null);
    }

    /** Every viewpoint id Archi defines, sorted; the empty "none" viewpoint is not listed. */
    public static SortedSet<String> known() {
        SortedSet<String> ids = known;
        if (ids == null) {
            ids = Collections.unmodifiableSortedSet(load());
            known = ids;
        }
        return ids;
    }

    private static SortedSet<String> load() {
        SortedSet<String> ids = new TreeSet<>();
        try {
            for (IViewpoint viewpoint : ViewpointManager.INSTANCE.getAllViewpoints()) {
                ids.add(viewpoint.getID());
            }
        } catch (LinkageError notInitialisable) {
            // Outside OSGi the registry cannot find its bundle; read the file it reads.
            ids.addAll(fromDefinitionsFile());
        }
        ids.remove("");
        if (ids.isEmpty()) {
            throw new IllegalStateException("Archi defines no viewpoints");
        }
        return ids;
    }

    private static SortedSet<String> fromDefinitionsFile() {
        try {
            File jar = new File(ViewpointManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File file = new File(jar.isDirectory() ? jar : jar.getParentFile(), "model/viewpoints.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList viewpoints = factory.newDocumentBuilder().parse(file).getElementsByTagName("viewpoint");
            SortedSet<String> ids = new TreeSet<>();
            for (int i = 0; i < viewpoints.getLength(); i++) {
                ids.add(((Element) viewpoints.item(i)).getAttribute("id"));
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read Archi's viewpoint definitions", e);
        }
    }
}
