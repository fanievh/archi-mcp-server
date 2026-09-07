package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IBounds;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AnchorPointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Builds connection response DTOs with absolute bendpoints and anchor points.
 *
 * <p>Extracted from ArchiModelAccessorImpl to improve cohesion.
 * Consolidates coordinate conversion, bendpoint handling, and connection DTO
 * construction. Package-visible — only ArchiModelAccessorImpl should use this.</p>
 */
final class ConnectionResponseBuilder {

    private ConnectionResponseBuilder() {}

    /**
     * Computes absolute bounds as {@code {x, y, width, height}} by walking the parent chain.
     *
     * <p>A nested view object stores coordinates relative to its immediate parent, so the absolute
     * position is the accumulated offset of every ancestor that is itself a diagram object. The
     * size is unaffected by nesting.
     *
     * <p>This is the untruncated box, and the render-face derivation needs it rather than the
     * centre: a caller holding only the published centre can rebuild the near edge exactly — the
     * same halving is added back — but lands a pixel short on the far edge of any odd dimension,
     * which over half the fixture corpus carries. Package-visible for testability.
     */
    static int[] computeAbsoluteBounds(IDiagramModelObject obj) {
        IBounds bounds = obj.getBounds();
        int x = bounds.getX();
        int y = bounds.getY();

        Object parent = obj.eContainer();
        while (parent instanceof IDiagramModelObject parentObj) {
            IBounds parentBounds = parentObj.getBounds();
            x += parentBounds.getX();
            y += parentBounds.getY();
            parent = parentObj.eContainer();
        }
        return new int[] { x, y, bounds.getWidth(), bounds.getHeight() };
    }

    /**
     * Computes absolute center coordinates by walking the parent chain.
     *
     * <p>Delegates to {@link #computeAbsoluteBounds} so the two walks cannot drift apart: they
     * would otherwise be two copies of the same ancestor accumulation, and a fix applied to one
     * would silently leave the other behind. The halving stays on the object's own size and
     * truncates, which is what {@code Rectangle.getCenter} does and therefore what the anchor
     * reference point this response publishes must be. Package-visible for testability.
     */
    static int[] computeAbsoluteCenter(IDiagramModelObject obj) {
        int[] bounds = computeAbsoluteBounds(obj);
        return new int[] { bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2 };
    }

    /**
     * Converts relative-offset bendpoints to absolute canvas coordinates, interpolating between the
     * two reconstructions Archi stores for every bendpoint — one anchored on the source centre, one
     * on the target centre — at the weight the renderer uses.
     *
     * <p>Archi draws bendpoint {@code i} of {@code n} at weight {@code (i + 1) / (n + 1)} along the
     * bendpoint list, interpolating from the source-anchored reconstruction toward the
     * target-anchored one. The renderer sets that weight in
     * {@code DiagramConnectionEditPart.refreshBendpoints} and applies it in draw2d's
     * {@code RelativeBendpoint.getLocation}, blending the two {@code ChopboxAnchor} reference
     * points. Those reference points are {@code Rectangle.getCenter()}, i.e. {@code x + width / 2}
     * on integer division — the same truncated centre {@link #computeAbsoluteCenter} produces, which
     * is why the centres passed in here are whole pixels and must stay that way.
     *
     * <p>The two reconstructions describe the same point only while both endpoints sit where they
     * sat when the route was written. While they agree, every weight yields the same coordinate and
     * this is exact. Once an endpoint has moved or been resized they disagree, and the rendered
     * polyline is drawn <em>sheared</em>: each point is displaced from the midpoint by
     * {@code (weight - 0.5) x drift}, most at the two terminal bendpoints and least in the middle.
     * Blending at a flat one-half would report the midpoint of that disagreement — a coordinate the
     * model holds on neither axis and the renderer draws at no index but the exact centre of an
     * odd-length list. The layout assessor measures the same disagreement before blending and
     * reports it as anchor drift; a caller that needs the values the model actually stores must read
     * the relative bendpoints instead.
     *
     * <p>The interpolation is carried in exact integer arithmetic — the numerator sums both
     * reconstructions already scaled by their integer weights, and a single division by
     * {@code n + 1} truncates toward zero, once. Evaluating the same weight in floating point, as
     * {@code DiagramModelUtils.getAbsoluteBendpointPositions} does, loses the last bits on any
     * weight that is not a binary fraction and can land a whole pixel low on a bendpoint whose two
     * reconstructions agree exactly. Integer arithmetic here keeps a zero-disagreement bendpoint
     * exact at every weight, which is what makes the absolute-to-relative round trip lossless.
     */
    static List<AbsoluteBendpointDto> convertRelativeToAbsolute(
            List<BendpointDto> relativeBendpoints,
            int srcCenterX, int srcCenterY,
            int tgtCenterX, int tgtCenterY) {
        int count = relativeBendpoints.size();
        List<AbsoluteBendpointDto> result = new ArrayList<>(count);
        int index = 0;
        for (BendpointDto bp : relativeBendpoints) {
            // Weight (index + 1) / (count + 1), carried as the two integer numerator terms so the
            // division happens once. srcWeight + tgtWeight == count + 1 by construction, so a
            // bendpoint whose two reconstructions agree resolves back to itself exactly.
            long tgtWeight = index + 1L;
            long srcWeight = count - (long) index;
            // Widened to long only to carry the products. Neither weight exceeds the bendpoint
            // count, which the caller does not bound, so a connection carrying a few thousand
            // bendpoints at large canvas coordinates would overflow a 32-bit product and wrap to a
            // nonsensical — possibly negative — coordinate, silently, since Java does not trap it.
            // The quotient is always back within a coordinate's range: it is a weighted mean of two
            // reconstructions, so it lies between them.
            int absX = (int) (((bp.startX() + (long) srcCenterX) * srcWeight
                    + (bp.endX() + (long) tgtCenterX) * tgtWeight) / (count + 1L));
            int absY = (int) (((bp.startY() + (long) srcCenterY) * srcWeight
                    + (bp.endY() + (long) tgtCenterY) * tgtWeight) / (count + 1L));
            result.add(new AbsoluteBendpointDto(absX, absY));
            index++;
        }
        return result;
    }

    /**
     * Converts absolute canvas coordinates to relative-offset bendpoint format.
     */
    static List<BendpointDto> convertAbsoluteToRelative(
            List<AbsoluteBendpointDto> absoluteBendpoints,
            int srcCenterX, int srcCenterY, int tgtCenterX, int tgtCenterY) {
        List<BendpointDto> result = new ArrayList<>(absoluteBendpoints.size());
        for (AbsoluteBendpointDto absBp : absoluteBendpoints) {
            int startX = absBp.x() - srcCenterX;
            int startY = absBp.y() - srcCenterY;
            int endX = absBp.x() - tgtCenterX;
            int endY = absBp.y() - tgtCenterY;
            result.add(new BendpointDto(startX, startY, endX, endY));
        }
        return result;
    }

    /**
     * Converts absolute to relative using view object centers.
     */
    static List<BendpointDto> convertAbsoluteToRelative(
            List<AbsoluteBendpointDto> absoluteBendpoints,
            IDiagramModelArchimateObject sourceViewObj,
            IDiagramModelArchimateObject targetViewObj) {
        int[] srcAbsCenter = computeAbsoluteCenter(sourceViewObj);
        int[] tgtAbsCenter = computeAbsoluteCenter(targetViewObj);
        return convertAbsoluteToRelative(absoluteBendpoints,
                srcAbsCenter[0], srcAbsCenter[1], tgtAbsCenter[0], tgtAbsCenter[1]);
    }

    /**
     * Reads EMF bendpoints into DTOs.
     */
    static List<BendpointDto> collectBendpoints(IDiagramModelArchimateConnection connection) {
        org.eclipse.emf.common.util.EList<IDiagramModelBendpoint> bps = connection.getBendpoints();
        if (bps == null || bps.isEmpty()) {
            return List.of();
        }
        List<BendpointDto> result = new ArrayList<>(bps.size());
        for (IDiagramModelBendpoint bp : bps) {
            result.add(new BendpointDto(bp.getStartX(), bp.getStartY(),
                    bp.getEndX(), bp.getEndY()));
        }
        return result;
    }

    /**
     * Resolves effective bendpoints from a connection's endpoints.
     */
    static List<BendpointDto> resolveEffectiveBendpointsFromConnection(
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            IDiagramModelArchimateConnection connection) {
        if (absoluteBendpoints == null || absoluteBendpoints.isEmpty()) {
            return bendpoints;
        }
        IConnectable srcConnectable = connection.getSource();
        IConnectable tgtConnectable = connection.getTarget();
        if (srcConnectable instanceof IDiagramModelArchimateObject srcObj
                && tgtConnectable instanceof IDiagramModelArchimateObject tgtObj) {
            return convertAbsoluteToRelative(absoluteBendpoints, srcObj, tgtObj);
        }
        throw new ModelAccessException(
                "Cannot use absoluteBendpoints: connection endpoints are not ArchiMate view objects",
                ErrorCode.INVALID_PARAMETER,
                null,
                "Use relative bendpoints (startX/startY/endX/endY) instead",
                null);
    }

    /**
     * Creates EMF bendpoint objects and applies them to a connection.
     */
    static void applyBendpointsToConnection(IDiagramModelArchimateConnection conn,
            List<BendpointDto> bendpoints) {
        if (bendpoints != null) {
            for (BendpointDto bp : bendpoints) {
                IDiagramModelBendpoint emfBp =
                        IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
                emfBp.setStartX(bp.startX());
                emfBp.setStartY(bp.startY());
                emfBp.setEndX(bp.endX());
                emfBp.setEndY(bp.endY());
                conn.getBendpoints().add(emfBp);
            }
        }
    }

    /**
     * Creates EMF bendpoint objects as a standalone list.
     */
    static List<IDiagramModelBendpoint> createEmfBendpoints(List<BendpointDto> bendpoints) {
        List<IDiagramModelBendpoint> result = new ArrayList<>();
        if (bendpoints != null) {
            for (BendpointDto bp : bendpoints) {
                IDiagramModelBendpoint emfBp =
                        IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
                emfBp.setStartX(bp.startX());
                emfBp.setStartY(bp.startY());
                emfBp.setEndX(bp.endX());
                emfBp.setEndY(bp.endY());
                result.add(emfBp);
            }
        }
        return result;
    }

    /**
     * The endpoint geometry a connection response reports: the two anchor reference points, the
     * absolute bendpoints derived against them, and the face each end is drawn attaching to.
     *
     * <p>Every field is null when it could not be derived, and null is omission on the wire.
     */
    record EndpointGeometry(AnchorPointDto sourceAnchor, AnchorPointDto targetAnchor,
                            List<AbsoluteBendpointDto> absoluteBendpoints,
                            String sourceRenderFace, String targetRenderFace) {

        /** Nothing derivable: an endpoint that is a group, a note or a view reference. */
        static final EndpointGeometry NONE = new EndpointGeometry(null, null, null, null, null);
    }

    /** The endpoint as an ArchiMate view object, or null when it is a group, note or reference. */
    static IDiagramModelArchimateObject archimateEndpoint(IConnectable endpoint) {
        return (endpoint instanceof IDiagramModelArchimateObject obj) ? obj : null;
    }

    /**
     * Describes both ends of a connection: reference points, absolute bendpoints, render faces.
     *
     * <p>Shared by every path that reports a connection, so the read-back, the prepare paths and
     * the bulk projection cannot describe the same geometry differently. It was previously
     * duplicated in the view collector, which recomputed the anchors and the absolute conversion
     * from its own copy of this block.
     *
     * @param responseBps the connection's stored bendpoints, or null when it has none
     */
    static EndpointGeometry describeEndpoints(IDiagramModelArchimateObject sourceViewObj,
                                              IDiagramModelArchimateObject targetViewObj,
                                              List<BendpointDto> responseBps) {
        if (sourceViewObj == null || targetViewObj == null) {
            return EndpointGeometry.NONE;
        }
        int[] srcBounds = computeAbsoluteBounds(sourceViewObj);
        int[] tgtBounds = computeAbsoluteBounds(targetViewObj);
        int[] srcCentre = RenderFaceDeriver.centreOf(srcBounds);
        int[] tgtCentre = RenderFaceDeriver.centreOf(tgtBounds);

        List<AbsoluteBendpointDto> absoluteBps = (responseBps == null) ? null
                : convertRelativeToAbsolute(responseBps, srcCentre[0], srcCentre[1],
                        tgtCentre[0], tgtCentre[1]);

        // The manhattan router ignores stored bendpoints entirely and aims each anchor at the
        // other element's centre; the default router aims at the outermost bendpoint when the
        // connection has any. The two are not interchangeable — over the fixture corpus they reach
        // the same face on fewer than half of the terminals that carry stored geometry — so which
        // one is in force has to be read rather than assumed, and when it cannot be read the face
        // is not derived at all.
        IDiagramModel view = commonView(sourceViewObj, targetViewObj);
        if (view == null) {
            return new EndpointGeometry(
                    new AnchorPointDto(srcCentre[0], srcCentre[1]),
                    new AnchorPointDto(tgtCentre[0], tgtCentre[1]),
                    absoluteBps, null, null);
        }

        boolean useBendpoints = absoluteBps != null && !absoluteBps.isEmpty()
                && view.getConnectionRouterType() != IDiagramModel.CONNECTION_ROUTER_MANHATTAN;
        int[] srcRef = useBendpoints
                ? new int[] { absoluteBps.get(0).x(), absoluteBps.get(0).y() } : tgtCentre;
        int[] tgtRef = useBendpoints
                ? new int[] { absoluteBps.get(absoluteBps.size() - 1).x(),
                              absoluteBps.get(absoluteBps.size() - 1).y() } : srcCentre;

        return new EndpointGeometry(
                new AnchorPointDto(srcCentre[0], srcCentre[1]),
                new AnchorPointDto(tgtCentre[0], tgtCentre[1]),
                absoluteBps,
                renderFace(sourceViewObj, srcBounds, tgtBounds, srcRef),
                renderFace(targetViewObj, tgtBounds, srcBounds, tgtRef));
    }

    /**
     * The view both endpoints sit on, or null when that cannot be established.
     *
     * <p>{@code getDiagramModel()} climbs {@code eContainer()} and answers null for an object that
     * is not attached to a view yet — which is exactly what a batch's queued object is, since it
     * carries its destination in the queue rather than in its container. Without a view there is no
     * router type, and without a router type there is no way to know whether the anchor aims at the
     * connection's own bendpoints or at the other element's centre; the two reach different faces
     * on most of the corpus, so guessing either would publish a measurement-shaped guess.
     *
     * <p>It also requires both endpoints to resolve, not just one. A pair with one endpoint still
     * queued would otherwise be derived from that endpoint's bounds accumulated over a parent chain
     * that is not attached either, which is a second way to be confidently wrong.
     */
    private static IDiagramModel commonView(IDiagramModelObject source, IDiagramModelObject target) {
        IDiagramModel sourceView = source.getDiagramModel();
        IDiagramModel targetView = target.getDiagramModel();
        return (sourceView != null && sourceView == targetView) ? sourceView : null;
    }

    /** The face one endpoint is drawn attaching to, or null when it cannot be derived. */
    private static String renderFace(IDiagramModelArchimateObject viewObj, int[] own, int[] remote,
                                     int[] reference) {
        IArchimateElement element = viewObj.getArchimateElement();
        String elementType = (element != null) ? element.eClass().getName() : null;
        RenderFaceDeriver.Anchoring anchoring = RenderFaceDeriver.anchoringOf(
                elementType, viewObj.getType(), own[2], own[3]);
        return RenderFaceDeriver.publishedFace(own, remote, reference[0], reference[1], anchoring);
    }

    /**
     * Re-reports a built connection with its styling read from the model.
     *
     * <p>Every path that reports a connection needs the builder's geometry <em>and</em> the
     * connection's own styling, and each used to assemble that by listing the components out again
     * at whatever arity it happened to know about. That is how a component the builder derives goes
     * missing: the rebuild silently truncates at its own arity, and nothing about adding a field to
     * the record makes anyone revisit the call site. Overlaying instead of relisting removes the
     * failure mode rather than fixing its latest instance.
     */
    static ViewConnectionDto withConnectionStyling(ViewConnectionDto base,
                                                   IDiagramModelArchimateConnection connection) {
        return withConnectionStyling(base,
                StylingHelper.readConnectionLineColor(connection),
                StylingHelper.readConnectionLineWidth(connection),
                StylingHelper.readConnectionFontColor(connection),
                StylingHelper.readConnectionNameVisible(connection),
                StylingHelper.readConnectionFontName(connection),
                StylingHelper.readConnectionFontSize(connection),
                StylingHelper.readConnectionFontStyle(connection),
                StylingHelper.readConnectionLabelExpression(connection),
                StylingHelper.readConnectionRelativePosition(connection));
    }

    /**
     * Re-reports a built connection with styling a caller computed — the post-mutation values a
     * prepare path resolves by merging the request over what the model currently holds.
     *
     * <p>The label expression and label offset are carried through from {@code base} rather than
     * taken as parameters: no prepare path computes them, and inventing arguments for them would
     * invite a caller to pass null and drop what the builder had.
     */
    static ViewConnectionDto withConnectionStyling(ViewConnectionDto base,
            String lineColor, Integer lineWidth, String fontColor, Boolean nameVisible,
            String fontName, Integer fontSize, String fontStyle) {
        return withConnectionStyling(base, lineColor, lineWidth, fontColor, nameVisible,
                fontName, fontSize, fontStyle, base.labelExpression(), base.relativePosition());
    }

    private static ViewConnectionDto withConnectionStyling(ViewConnectionDto base,
            String lineColor, Integer lineWidth, String fontColor, Boolean nameVisible,
            String fontName, Integer fontSize, String fontStyle,
            String labelExpression, Integer relativePosition) {
        return new ViewConnectionDto(
                base.viewConnectionId(), base.relationshipId(), base.relationshipType(),
                base.sourceViewObjectId(), base.targetViewObjectId(), base.bendpoints(),
                base.absoluteBendpoints(), base.sourceAnchor(), base.targetAnchor(),
                base.textPosition(),
                lineColor, lineWidth, fontColor, nameVisible, fontName, fontSize, fontStyle,
                labelExpression, relativePosition,
                base.sourceRenderFace(), base.targetRenderFace());
    }

    /**
     * Builds a ViewConnectionDto response with absolute bendpoints, anchor points and render faces.
     */
    static ViewConnectionDto buildConnectionResponseDto(
            String viewConnectionId, IArchimateRelationship relationship,
            String sourceVoId, String targetVoId, List<BendpointDto> effectiveBendpoints,
            IDiagramModelArchimateObject sourceViewObj,
            IDiagramModelArchimateObject targetViewObj,
            Integer textPosition) {
        List<BendpointDto> responseBps = (effectiveBendpoints != null
                && !effectiveBendpoints.isEmpty()) ? effectiveBendpoints : null;

        EndpointGeometry geometry = describeEndpoints(sourceViewObj, targetViewObj, responseBps);

        return new ViewConnectionDto(viewConnectionId, relationship.getId(),
                relationship.eClass().getName(), sourceVoId, targetVoId,
                responseBps, geometry.absoluteBendpoints(),
                geometry.sourceAnchor(), geometry.targetAnchor(), textPosition,
                null, null, null, null, null, null, null, null, null,
                geometry.sourceRenderFace(), geometry.targetRenderFace());
    }
}
