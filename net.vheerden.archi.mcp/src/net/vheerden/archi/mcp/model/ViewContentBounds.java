package net.vheerden.archi.mcp.model;

import java.util.List;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

/**
 * The box a view's content occupies, in absolute canvas coordinates: every diagram object at any
 * depth except notes, widened by every connection bendpoint, so that a box built from it clears the
 * routed paths that arc past the elements as well as the elements themselves.
 *
 * <p>Notes are left out so that the box describes the diagram and not its annotations. Two callers
 * read it: {@code get-content-bounds}, which sizes an exported picture and the raster memory check,
 * and note placement by {@code position}, which anchors a new note above or below the content.</p>
 */
final class ViewContentBounds {

    private ViewContentBounds() {
    }

    /**
     * The box a note placed by {@code position} anchors to: {@code content} united with every box in
     * {@code notes}, so a note placed above or below the content clears the notes already there,
     * and on a view holding only notes it anchors to them. Null when there is neither.
     *
     * @param content the content bounds from {@link #of}, or null when the view holds only notes
     * @param notes   absolute {x, y, width, height} boxes of the notes the view will hold
     */
    static ContentBounds unitedWith(ContentBounds content, List<int[]> notes) {
        if (notes.isEmpty()) {
            return content;
        }
        double minX = content == null ? Double.MAX_VALUE : content.x();
        double minY = content == null ? Double.MAX_VALUE : content.y();
        double maxX = content == null ? -Double.MAX_VALUE : content.x() + content.width();
        double maxY = content == null ? -Double.MAX_VALUE : content.y() + content.height();
        for (int[] note : notes) {
            minX = Math.min(minX, note[0]);
            minY = Math.min(minY, note[1]);
            maxX = Math.max(maxX, note[0] + note[2]);
            maxY = Math.max(maxY, note[1] + note[3]);
        }
        return new ContentBounds(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * The content bounds of {@code diagramModel}, or null when it holds no object other than notes.
     */
    static ContentBounds of(IArchimateDiagramModel diagramModel) {
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

        // Filter out notes — content bounds should reflect diagram content only,
        // so existing notes don't push position-based placement further away.
        List<AssessmentNode> contentNodes = nodes.stream()
                .filter(n -> !n.isNote())
                .toList();

        if (contentNodes.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (AssessmentNode node : contentNodes) {
            if (node.x() < minX) minX = node.x();
            if (node.y() < minY) minY = node.y();
            double right = node.x() + node.width();
            double bottom = node.y() + node.height();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }

        // Extend bounds to include connection bendpoints that may extend
        // beyond element boundaries (e.g., routed paths arcing above/below content).
        for (IDiagramModelConnection conn : AssessmentCollector.collectAllConnections(diagramModel)) {
            IConnectable source = conn.getSource();
            IConnectable target = conn.getTarget();
            if (!(source instanceof IDiagramModelObject sourceObj)
                    || !(target instanceof IDiagramModelObject targetObj)) {
                continue;
            }
            int[] srcCenter = ConnectionResponseBuilder.computeAbsoluteCenter(sourceObj);
            int[] tgtCenter = ConnectionResponseBuilder.computeAbsoluteCenter(targetObj);
            // Bendpoints are stored as relative offsets from the source and target centres, and
            // the drawn point interpolates the two reconstructions at Archi's render weight,
            // (i + 1) / (n + 1). Averaging them instead would understate how far a drifted
            // connection reaches near its terminals — where the shear is largest — and these
            // bounds exist so note placement clears the connections that extend past the
            // elements. Kept in floating point: this feeds a bounding box, not a reported point.
            int bendpointCount = conn.getBendpoints().size();
            int bendpointIndex = 0;
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                double weight = (bendpointIndex + 1.0) / (bendpointCount + 1.0);
                bendpointIndex++;
                double absX = (bp.getStartX() + srcCenter[0]) * (1.0 - weight)
                        + (bp.getEndX() + tgtCenter[0]) * weight;
                double absY = (bp.getStartY() + srcCenter[1]) * (1.0 - weight)
                        + (bp.getEndY() + tgtCenter[1]) * weight;
                if (absX < minX) minX = absX;
                if (absY < minY) minY = absY;
                if (absX > maxX) maxX = absX;
                if (absY > maxY) maxY = absY;
            }
        }

        return new ContentBounds(minX, minY, maxX - minX, maxY - minY);
    }
}
