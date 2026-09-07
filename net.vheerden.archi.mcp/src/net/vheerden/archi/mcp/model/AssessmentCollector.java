package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWTError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
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
import com.archimatetool.model.ITextPosition;

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
            // Both kinds of transparent labelled box, resolved by the same predicate the
            // arrangement family uses, so "what counts as a container" has one definition in this
            // package rather than two that can drift. isTarget admits a native group as well, so
            // this is a superset of isGroup — see AssessmentNode.
            boolean isContainer = TopLevelGroupTargets.isTarget(child);

            // Extract name and pre-compute label text width
            String name = child.getName();
            double labelTextWidth = 0.0;
            if (name != null && !name.isEmpty() && !isGroup && !isNote) {
                try {
                    labelTextWidth = ElementSizer.measureText(name).textWidth();
                } catch (Exception | SWTError e) {
                    // SWTError as well as Exception: a display-less Display.getDefault() raises
                    // one or the other depending on the platform, and an Error escapes a catch
                    // written only for Exception — which is what took the headless lane down once
                    // a placement path reached this walk. Same pairing, and the same reasoning, as
                    // ElementSizer.fitTextBoxHeightToContentOrElse. The fallback is honest: the
                    // one field that could not be measured stays at its zero sentinel and every
                    // geometric field on the node is unaffected.
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
                    // Resolve the owning model for archive access. On a DETACHED
                    // copy (the route-normalized baseline probe measures one) the
                    // diagram has no container, so getArchimateModel() is null;
                    // fall back to the archimate concept's model — the copy still
                    // references the live concept, so its model resolves the same
                    // archive the live assessment uses. Equivalent for the live
                    // path; keeps the copy's image sizing byte-equal to the bare
                    // baseline instead of silently falling back to a fixed icon.
                    IArchimateModel imageModel =
                            (child.getDiagramModel() == null) ? null
                                    : child.getDiagramModel().getArchimateModel();
                    if (imageModel == null
                            && child instanceof IDiagramModelArchimateObject archiChild
                            && archiChild.getArchimateConcept() != null) {
                        imageModel = archiChild.getArchimateConcept().getArchimateModel();
                    }
                    int[] dims = (imageModel == null) ? null
                            : ImageHelper.readNaturalImageDimensions(imageModel, imgPath);
                    if (dims != null) {
                        imageNaturalWidth = dims[0];
                        imageNaturalHeight = dims[1];
                    }
                } catch (Exception | SWTError e) {
                    // Decodes through SWT too, so it is guarded identically — no reader should
                    // have to work out which of these three walks is the one that can kill the
                    // process on a host without a display.
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
                    } catch (Exception | SWTError e) {
                        // Measures through the same SWT path as the label above, so it carries the
                        // same Error-vs-Exception hazard and takes the same guard.
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

            // Horizontal label alignment, read PER OBJECT. Archi places the glyph run inside the
            // figure from this feature, so it decides where the title actually renders. Three
            // populations reach here: types whose default is LEFT (Grouping, group, note), which
            // both Archi's palette and this server stamp at creation; plain elements, which this
            // server leaves at CENTRE while Archi derives theirs from a user preference, so those
            // two can disagree on a host where the preference was changed; objects this server
            // created before it stamped anything, which keep CENTRE; and any object whose
            // alignment a caller set explicitly. Reading the object (rather than assuming a
            // per-type value) is the only way the assessor sees the title where it really is.
            int textAlignment = child.getTextAlignment();

            // Vertical label position, read PER OBJECT for the same reason and through the same
            // mechanism: Archi builds ONE GridData for the title control and reads its horizontal
            // alignment from getTextAlignment() and its vertical from getTextPosition(). Assuming a
            // constant here is the same class of error as assuming a constant alignment was — and
            // this server publishes a verticalTextAlignment parameter that writes it, so a
            // non-TOP title is reachable through this server on any object.
            //
            // The interface is optional, so this is an instanceof rather than a plain call. Note
            // the receiver: IDiagramModelConnection ALSO has a getTextPosition(), meaning the
            // label's position along the connection — a different feature with the same name.
            // This one is the OBJECT's, via ITextPosition.
            int textPosition = child instanceof ITextPosition tp
                    ? tp.getTextPosition()
                    : AssessmentNode.TEXT_POSITION_TOP;

            nodes.add(new AssessmentNode(child.getId(),
                    absX, absY, w, h, parentId, isGroup, isNote,
                    name, labelTextWidth, imgPath, imgPosition, noteRequiredHeight,
                    imageNaturalWidth, imageNaturalHeight, isJunction, child.getFillColor(),
                    isContainer, textAlignment, textPosition));

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

            // The largest disagreement between a bendpoint's two stored reconstructions, per axis.
            // Measured BEFORE the blend below discards it: the midpoint hands every detector a
            // plausible-looking polyline whichever way the anchors disagree, so this is the only
            // point at which the disagreement is observable.
            double anchorDriftX = 0.0;
            double anchorDriftY = 0.0;

            // Archi draws bendpoint i of n at weight (i + 1) / (n + 1) along the bendpoint list,
            // interpolating from the source-anchored reconstruction toward the target-anchored one.
            // Measuring over a flat one-half blend would hand every detector a polyline the
            // renderer draws only at the exact centre of an odd-length list: while the two
            // reconstructions disagree the drawn path is sheared, most at its terminal bendpoints,
            // and the terminal segments are precisely what the terminal-geometry dimensions judge.
            // Centres stay untruncated here — the drift measured just above is read off them before
            // the blend, and rounding them would quantise away the sub-pixel disagreement that is
            // the whole of what it reports.
            int bendpointCount = conn.getBendpoints().size();
            int bendpointIndex = 0;
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                double srcDerivedX = bp.getStartX() + srcCenterX;
                double tgtDerivedX = bp.getEndX() + tgtCenterX;
                double srcDerivedY = bp.getStartY() + srcCenterY;
                double tgtDerivedY = bp.getEndY() + tgtCenterY;
                anchorDriftX = Math.max(anchorDriftX, Math.abs(srcDerivedX - tgtDerivedX));
                anchorDriftY = Math.max(anchorDriftY, Math.abs(srcDerivedY - tgtDerivedY));

                double weight = (bendpointIndex + 1.0) / (bendpointCount + 1.0);
                double absX = srcDerivedX * (1.0 - weight) + tgtDerivedX * weight;
                double absY = srcDerivedY * (1.0 - weight) + tgtDerivedY * weight;
                pathPoints.add(new double[]{absX, absY});
                bendpointIndex++;
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
                    labelText, textPosition, relativePosition, anchorDriftX, anchorDriftY));
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
