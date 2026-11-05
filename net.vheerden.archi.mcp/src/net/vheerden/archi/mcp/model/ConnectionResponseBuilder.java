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

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
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
     * Converts relative-offset bendpoints to absolute canvas coordinates: the whole pixel at which
     * Archi's renderer draws each one.
     *
     * <p>Archi stores every bendpoint twice, once as an offset from the source centre and once from
     * the target centre, and draws bendpoint {@code i} of {@code n} at weight {@code (i + 1) / (n + 1)}
     * from the source-anchored reconstruction toward the target-anchored one. The renderer sets that
     * weight in {@code DiagramConnectionEditPart.refreshBendpoints} and applies it in draw2d's
     * {@code RelativeBendpoint.getLocation}, blending the two {@code ChopboxAnchor} reference points.
     * Those reference points are {@code Rectangle.getCenter()}, i.e. {@code x + width / 2} on integer
     * division — the same truncated centre {@link #computeAbsoluteCenter} produces, which is why the
     * centres passed in here are whole pixels and must stay that way.</p>
     *
     * <p>The arithmetic is draw2d's own, through
     * {@link GeometryUtils#drawnBendpointCoordinate}: the weight held as a {@code float}, the blend
     * taken in {@code double}, and the result floored, not truncated. Any other arithmetic reports a
     * point the line is not drawn through on a connection whose two reconstructions disagree: a
     * truncation lands a pixel high where the blend is negative and not whole, and an exact weight
     * lands a pixel high where the float weight puts a whole blend just below itself. Reporting the
     * drawn pixel there is the point of this field, and of every internal reader that reasons about
     * where the line is, the terminals-only re-route among them.</p>
     *
     * <p>The two reconstructions describe the same point only while both endpoints sit where they
     * sat when the route was written. While they agree, the blend is that point at every weight,
     * exactly: the floor's {@code 1e-9} margin absorbs the double's last-bit error at canvas
     * magnitudes, so the absolute-to-relative round trip stays lossless. Once an endpoint has moved
     * or been resized they disagree, and the rendered polyline is drawn <em>sheared</em>: each point
     * is displaced from the midpoint by {@code (weight - 0.5) x drift}, most at the two terminal
     * bendpoints and least in the middle. The layout assessor measures the same disagreement before
     * blending and reports it as anchor drift; a caller that needs the values the model actually
     * stores must read the relative bendpoints instead.</p>
     */
    static List<AbsoluteBendpointDto> convertRelativeToAbsolute(
            List<BendpointDto> relativeBendpoints,
            int srcCenterX, int srcCenterY,
            int tgtCenterX, int tgtCenterY) {
        int count = relativeBendpoints.size();
        List<AbsoluteBendpointDto> result = new ArrayList<>(count);
        int index = 0;
        for (BendpointDto bp : relativeBendpoints) {
            // Each centre is passed as an origin with a zero size, so the helper's reference point
            // origin + size / 2 is the centre itself.
            int absX = GeometryUtils.drawnBendpointCoordinate(bp.startX(), bp.endX(),
                    srcCenterX, 0, tgtCenterX, 0, index, count);
            int absY = GeometryUtils.drawnBendpointCoordinate(bp.startY(), bp.endY(),
                    srcCenterY, 0, tgtCenterY, 0, index, count);
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
        // A connection whose add has not run yet has no ends at all, which is a different cause
        // from an end that is not an element, and it has different remedies.
        if (srcConnectable == null || tgtConnectable == null) {
            throw new ModelAccessException(
                    "Cannot use absoluteBendpoints: connection '" + connection.getId()
                            + "' was added earlier in this batch or bulk-mutate and is not connected to"
                            + " its ends until that is committed",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Give the route as absoluteBendpoints on the add-connection-to-view that adds it,"
                            + " use relative bendpoints (startX/startY/endX/endY), or update the connection"
                            + " once it is committed: after end-batch, or after the bulk-mutate returns"
                            + " when no batch is open",
                    null);
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

        /**
         * Nothing reported on either end, because one end is another connection or its bounds
         * cannot be read. The faces and absolute bendpoints need both ends, and the readable end's
         * anchor is withheld with them (see {@link ConnectionResponseBuilder#describeEndpoints}).
         */
        static final EndpointGeometry NONE = new EndpointGeometry(null, null, null, null, null);
    }

    /**
     * The endpoint as an ArchiMate view object, or null when it is anything else. For an ArchiMate
     * connection that is another connection: Archi refuses a group, note, image or view reference.
     */
    static IDiagramModelArchimateObject archimateEndpoint(IConnectable endpoint) {
        return (endpoint instanceof IDiagramModelArchimateObject obj) ? obj : null;
    }

    /**
     * The id to report for one end of a connection, whatever kind of object that end is.
     *
     * <p>An end that is another connection is named by that connection's id, as
     * {@code get-view-contents} names it; deriving the id from {@link #archimateEndpoint} instead
     * would report such an end as absent. The id is null only when the connection has no end yet:
     * one added earlier in the same batch or call is detached until its add command runs, and
     * that command is what sets its ends.</p>
     */
    static String endId(IConnectable end) {
        return (end == null) ? null : end.getId();
    }

    /**
     * Describes both ends of a connection: reference points, absolute bendpoints, render faces.
     *
     * <p>Shared by every path that reports a connection, so the read-back, the prepare paths and
     * the bulk projection cannot describe the same geometry differently. It was previously
     * duplicated in the view collector, which recomputed the anchors and the absolute conversion
     * from its own copy of this block.
     *
     * <p>When either end is null, both ends abstain. Callers pass null for an end that is another
     * connection, and the bulk projection also passes null for a figure whose bounds cannot be
     * read. On Archi 5.8 to 5.10, an end that is another connection works like this. Archi draws it
     * at the midpoint of the remote connection's point list. Its reference point is the centre of
     * the remote connection figure's bounds, and draw2d widens those bounds to include the
     * figure's children: the label and the arrowhead decorations. The label's size depends on font
     * metrics, and the remote connection's drawn ends depend on its own anchors, so that reference
     * point cannot be read from the model. Archi's relative bendpoints blend both ends' reference
     * points, so the absolute bendpoints cannot be derived. The readable end's face aims at the
     * remote reference point, or at one of those bendpoints, so it cannot be derived either. The
     * readable end's own centre is only half of what the bendpoint formula needs, so it is not
     * reported.
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
                connection);
    }

    /**
     * Re-reports a built connection with styling a caller computed — the post-mutation values a
     * prepare path resolves by merging the request over what the model currently holds.
     *
     * <p>The label expression, label offset, text alignment and properties are read off
     * {@code connection} rather than taken as parameters: neither connection tool that answers with this
     * row changes any of them (only the routing tools' label passes move a label offset, and they
     * answer with their own report), so what the connection holds is what it will hold
     * after the call. Carrying them over from
     * {@code base} instead dropped them, because the builder never sets them.
     */
    static ViewConnectionDto withConnectionStyling(ViewConnectionDto base,
            IDiagramModelArchimateConnection connection,
            String lineColor, Integer lineWidth, String fontColor, Boolean nameVisible,
            String fontName, Integer fontSize, String fontStyle) {
        return withConnectionStyling(base, lineColor, lineWidth, fontColor, nameVisible,
                fontName, fontSize, fontStyle, connection);
    }

    private static ViewConnectionDto withConnectionStyling(ViewConnectionDto base,
            String lineColor, Integer lineWidth, String fontColor, Boolean nameVisible,
            String fontName, Integer fontSize, String fontStyle,
            IDiagramModelArchimateConnection connection) {
        return new ViewConnectionDto(
                base.viewConnectionId(), base.relationshipId(), base.relationshipType(),
                base.sourceViewObjectId(), base.targetViewObjectId(), base.bendpoints(),
                base.absoluteBendpoints(), base.sourceAnchor(), base.targetAnchor(),
                base.textPosition(),
                lineColor, lineWidth, fontColor, nameVisible, fontName, fontSize, fontStyle,
                StylingHelper.readConnectionLabelExpression(connection),
                StylingHelper.readConnectionRelativePosition(connection),
                base.sourceRenderFace(), base.targetRenderFace(),
                StylingHelper.readConnectionTextAlignment(connection),
                NonArchimateConnectionMapper.properties(connection));
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
