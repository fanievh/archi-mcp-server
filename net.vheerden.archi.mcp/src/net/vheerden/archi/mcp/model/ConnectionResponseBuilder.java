package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
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
 * <p>Extracted from ArchiModelAccessorImpl (Story 12-4) to improve cohesion.
 * Consolidates coordinate conversion, bendpoint handling, and connection DTO
 * construction. Package-visible — only ArchiModelAccessorImpl should use this.</p>
 */
final class ConnectionResponseBuilder {

    private ConnectionResponseBuilder() {}

    /**
     * Computes absolute center coordinates by walking the parent chain.
     * Package-visible for testability.
     */
    static int[] computeAbsoluteCenter(IDiagramModelObject obj) {
        IBounds bounds = obj.getBounds();
        int centerX = bounds.getX() + bounds.getWidth() / 2;
        int centerY = bounds.getY() + bounds.getHeight() / 2;

        Object parent = obj.eContainer();
        while (parent instanceof IDiagramModelObject parentObj) {
            IBounds parentBounds = parentObj.getBounds();
            centerX += parentBounds.getX();
            centerY += parentBounds.getY();
            parent = parentObj.eContainer();
        }
        return new int[] { centerX, centerY };
    }

    /**
     * Converts relative-offset bendpoints to absolute canvas coordinates
     * using averaged source and target offsets.
     */
    static List<AbsoluteBendpointDto> convertRelativeToAbsolute(
            List<BendpointDto> relativeBendpoints,
            int srcCenterX, int srcCenterY,
            int tgtCenterX, int tgtCenterY) {
        List<AbsoluteBendpointDto> result = new ArrayList<>(relativeBendpoints.size());
        for (BendpointDto bp : relativeBendpoints) {
            int absX = (bp.startX() + srcCenterX + bp.endX() + tgtCenterX) / 2;
            int absY = (bp.startY() + srcCenterY + bp.endY() + tgtCenterY) / 2;
            result.add(new AbsoluteBendpointDto(absX, absY));
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
     * Builds a ViewConnectionDto response with absolute bendpoints and anchor points.
     */
    static ViewConnectionDto buildConnectionResponseDto(
            String viewConnectionId, IArchimateRelationship relationship,
            String sourceVoId, String targetVoId, List<BendpointDto> effectiveBendpoints,
            IDiagramModelArchimateObject sourceViewObj,
            IDiagramModelArchimateObject targetViewObj,
            Integer textPosition) {
        List<BendpointDto> responseBps = (effectiveBendpoints != null
                && !effectiveBendpoints.isEmpty()) ? effectiveBendpoints : null;

        AnchorPointDto sourceAnchor = null;
        AnchorPointDto targetAnchor = null;
        List<AbsoluteBendpointDto> absoluteBps = null;

        if (sourceViewObj != null && targetViewObj != null) {
            int[] srcAbsCenter = computeAbsoluteCenter(sourceViewObj);
            int[] tgtAbsCenter = computeAbsoluteCenter(targetViewObj);

            sourceAnchor = new AnchorPointDto(srcAbsCenter[0], srcAbsCenter[1]);
            targetAnchor = new AnchorPointDto(tgtAbsCenter[0], tgtAbsCenter[1]);

            if (responseBps != null) {
                absoluteBps = convertRelativeToAbsolute(responseBps,
                        srcAbsCenter[0], srcAbsCenter[1], tgtAbsCenter[0], tgtAbsCenter[1]);
            }
        }

        return new ViewConnectionDto(viewConnectionId, relationship.getId(),
                relationship.eClass().getName(), sourceVoId, targetVoId,
                responseBps, absoluteBps, sourceAnchor, targetAnchor, textPosition);
    }
}
