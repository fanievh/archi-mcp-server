package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IJunction;

/**
 * Collects assessment nodes and connections from diagram models for
 * layout quality assessment and routing.
 *
 * <p>Extracted from ArchiModelAccessorImpl to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class AssessmentCollector {

    private AssessmentCollector() {}

    private static final Logger logger = LoggerFactory.getLogger(AssessmentCollector.class);

    static List<AssessmentNode> collectAssessmentNodes(
            IArchimateDiagramModel diagramModel) {
        List<AssessmentNode> nodes = new ArrayList<>();
        collectAssessmentNodesRecursive(diagramModel, null, 0.0, 0.0, nodes);
        return nodes;
    }

    static void collectAssessmentNodesRecursive(
            IDiagramModelContainer container, String parentId,
            double parentOffsetX, double parentOffsetY,
            List<AssessmentNode> nodes) {
        for (IDiagramModelObject child : container.getChildren()) {
            IBounds bounds = child.getBounds();
            double w = bounds.getWidth();
            double h = bounds.getHeight();
            if (w <= 0 || h <= 0) {
                logger.warn("Skipping element '{}' (id={}) with zero/negative bounds: w={}, h={}",
                        child.getName(), child.getId(), w, h);
                continue;
            }
            double absX = bounds.getX() + parentOffsetX;
            double absY = bounds.getY() + parentOffsetY;
            boolean isGroup = child instanceof IDiagramModelGroup;
            boolean isNote = child instanceof IDiagramModelNote;

            // Extract name and pre-compute label text width
            String name = child.getName();
            double labelTextWidth = 0.0;
            if (name != null && !name.isEmpty() && !isGroup && !isNote) {
                try {
                    labelTextWidth = ElementSizer.measureText(name).textWidth();
                } catch (Exception e) {
                    logger.warn("Failed to measure text for '{}': {}", name, e.getMessage());
                }
            }

            // Extract image path and position. A custom image lives on the diagram
            // object (IIconic); a specialization image lives on the element's profile
            // and is surfaced via the profile image source — resolve that too so the
            // overlap detector can see specialization icons, not just custom images.
            String imgPath = ImageHelper.readImagePath(child);
            String imgPosition = null;
            if (imgPath != null && !imgPath.isEmpty()) {
                imgPosition = ImageHelper.readImagePosition(child);
                if (imgPosition == null) {
                    imgPosition = "top-right"; // Archi default
                }
            } else {
                imgPath = ImageHelper.readProfileImagePath(child); // null unless a profile icon
                if (imgPath != null) {
                    imgPosition = "top-right"; // specialization decorator renders top-right
                }
            }

            // For image-bearing elements, pre-compute the image's true archive pixel
            // size so the overlap detector can size the image rect from what actually
            // renders rather than a fixed icon assumption. 0.0 when there is no image
            // or the archive read is unavailable (e.g. headless) — the detector then
            // falls back to its fixed icon size. Pre-computed here (archive access is
            // on the accessor thread) to keep LayoutQualityAssessor archive/SWT-free.
            double imageNaturalWidth = 0.0;
            double imageNaturalHeight = 0.0;
            if (imgPath != null) {
                try {
                    // getDiagramModel() can be null for a detached child; treat that as
                    // "dimensions unavailable" rather than letting it NPE into the catch.
                    int[] dims = (child.getDiagramModel() == null) ? null
                            : ImageHelper.readNaturalImageDimensions(
                                    child.getDiagramModel().getArchimateModel(), imgPath);
                    if (dims != null) {
                        imageNaturalWidth = dims[0];
                        imageNaturalHeight = dims[1];
                    }
                } catch (Exception e) {
                    logger.warn("Failed to read image dimensions for id={}: {}",
                            child.getId(), e.getMessage());
                }
            }

            // For notes, pre-compute the wrapped height the content needs at the note's
            // CURRENT width — mirroring the auto-fit path (ArchiModelAccessorImpl note
            // sizing: width - HORIZONTAL_TEXT_INSET, LABEL_VERTICAL_PADDING, cap
            // MAX_NOTE_HEIGHT) so the assessor can flag clipped notes by pure geometry.
            // Real SWT measurement (no LABEL_RENDER_WIDTH_FACTOR fudge). Stored content is
            // already the rendered form (escapes interpreted at write time) — measure as-is.
            // minHeight=1 so the floor never masks a clip. 0.0 for non-notes / empty content.
            double noteRequiredHeight = 0.0;
            if (isNote && child instanceof IDiagramModelNote note) {
                String content = note.getContent();
                // isBlank (not isEmpty): a whitespace-only note renders empty — nothing to
                // clip — so leave it at the 0.0 skip sentinel rather than measuring a stray line.
                if (content != null && !content.isBlank()) {
                    try {
                        int noteContentWidth = Math.max(1, (int) w - ElementSizer.HORIZONTAL_TEXT_INSET);
                        noteRequiredHeight = ElementSizer.fitTextBoxHeightToContent(
                                content, noteContentWidth, ElementSizer.LABEL_VERTICAL_PADDING,
                                1, ElementSizer.MAX_NOTE_HEIGHT);
                    } catch (Exception e) {
                        logger.warn("Failed to measure note content for id={}: {}",
                                child.getId(), e.getMessage());
                    }
                }
            }

            // A Junction renders as a solid dark shape with no usable interior, so the own-endpoint
            // label-overlap check must apply a near-zero overlap bar to it (any label on its fill is
            // unreadable) rather than the box-tolerant bar that suits a normal element.
            boolean isJunction = child instanceof IDiagramModelArchimateObject archiObj
                    && archiObj.getArchimateConcept() instanceof IJunction;

            nodes.add(new AssessmentNode(child.getId(),
                    absX, absY, w, h, parentId, isGroup, isNote,
                    name, labelTextWidth, imgPath, imgPosition, noteRequiredHeight,
                    imageNaturalWidth, imageNaturalHeight, isJunction, child.getFillColor()));

            if (child instanceof IDiagramModelContainer nested) {
                collectAssessmentNodesRecursive(nested, child.getId(),
                        absX, absY, nodes);
            }
        }
    }

    static List<AssessmentConnection> collectAssessmentConnections(
            IArchimateDiagramModel diagramModel,
            List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<AssessmentConnection> connections = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (IDiagramModelConnection conn : collectAllConnections(diagramModel)) {
            if (!seen.add(conn.getId())) continue;

            IConnectable source = conn.getSource();
            IConnectable target = conn.getTarget();
            if (!(source instanceof IDiagramModelObject)
                    || !(target instanceof IDiagramModelObject)) {
                continue;
            }

            AssessmentNode srcNode = nodeMap.get(source.getId());
            AssessmentNode tgtNode = nodeMap.get(target.getId());
            if (srcNode == null || tgtNode == null) continue;

            double srcCenterX = srcNode.x() + srcNode.width() / 2;
            double srcCenterY = srcNode.y() + srcNode.height() / 2;
            double tgtCenterX = tgtNode.x() + tgtNode.width() / 2;
            double tgtCenterY = tgtNode.y() + tgtNode.height() / 2;

            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[]{srcCenterX, srcCenterY});

            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                double absX = (bp.getStartX() + srcCenterX
                        + bp.getEndX() + tgtCenterX) / 2;
                double absY = (bp.getStartY() + srcCenterY
                        + bp.getEndY() + tgtCenterY) / 2;
                pathPoints.add(new double[]{absX, absY});
            }

            pathPoints.add(new double[]{tgtCenterX, tgtCenterY});

            // A connection whose label is suppressed (name not visible) reserves no
            // label box: an unrendered label cannot overlap anything, so resolve it to
            // empty and let the assessor's empty-label guard skip it. Keeps label
            // suppression an honest escape hatch and matches the layout-reservation path.
            String labelText = "";
            if (conn.isNameVisible() && conn instanceof IDiagramModelArchimateConnection archConn) {
                IArchimateRelationship rel = archConn.getArchimateRelationship();
                labelText = (rel != null && rel.getName() != null) ? rel.getName() : "";
            }
            int textPosition = conn.getTextPosition();
            // The applied "Label Offset" anchor (CENTER on a platform without the feature) so the own-endpoint
            // check can credit a label that has already been lifted off its box.
            int relativePosition = RelativePositionFeature.get(conn);

            connections.add(new AssessmentConnection(
                    conn.getId(), source.getId(), target.getId(), pathPoints,
                    labelText, textPosition, relativePosition));
        }

        return connections;
    }

    static List<IDiagramModelConnection> collectAllConnections(
            IArchimateDiagramModel diagramModel) {
        Set<String> seen = new HashSet<>();
        List<IDiagramModelConnection> result = new ArrayList<>();
        collectConnectionsRecursive(diagramModel, seen, result);
        return result;
    }

    private static void collectConnectionsRecursive(
            IDiagramModelContainer container,
            Set<String> seen,
            List<IDiagramModelConnection> result) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (seen.add(conn.getId())) {
                    result.add(conn);
                }
            }
            for (IDiagramModelConnection conn : child.getTargetConnections()) {
                if (seen.add(conn.getId())) {
                    result.add(conn);
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectConnectionsRecursive(nested, seen, result);
            }
        }
    }
}
