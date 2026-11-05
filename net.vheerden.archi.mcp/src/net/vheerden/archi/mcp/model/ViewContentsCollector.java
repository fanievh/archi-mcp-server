package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_GROUP_HEIGHT;
import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_GROUP_WIDTH;
import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_IMAGE_VISUAL_HEIGHT;
import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_IMAGE_VISUAL_WIDTH;
import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_NOTE_HEIGHT;
import static net.vheerden.archi.mcp.model.ArchiModelAccessorImpl.DEFAULT_NOTE_WIDTH;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;

import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.NonArchimateConnectionDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Reads a view for {@code get-view-contents}: every object it holds, at any depth, into the response's
 * arrays, and the connections drawn from them.
 *
 * <p>Kept out of the accessor, which is held to a size ceiling, so the read can grow here. One instance
 * per read; it holds the arrays while the walk fills them.</p>
 */
final class ViewContentsCollector {

    private final List<ElementDto> elements = new ArrayList<>();
    private final List<RelationshipDto> relationships = new ArrayList<>();
    private final List<ViewNodeDto> visualMetadata = new ArrayList<>();
    private final List<ViewConnectionDto> connections = new ArrayList<>();
    private final List<ViewGroupDto> groups = new ArrayList<>();
    private final List<ViewNoteDto> notes = new ArrayList<>();
    private final List<DiagramImageDto> images = new ArrayList<>();
    private final List<EmbeddedViewDto> viewReferences = new ArrayList<>();
    private final List<NonArchimateConnectionDto> nonArchimateConnections = new ArrayList<>();
    private final Set<String> seenElementIds = new HashSet<>();
    private final Set<String> seenRelationshipIds = new HashSet<>();

    private ViewContentsCollector() {
    }

    /**
     * The contents of {@code view}. The element and relationship arrays are deduplicated by concept id;
     * every other array has one row per drawn object or connection. The arrays a view may hold nothing
     * of (groups, notes, images, view references, connections that are not ArchiMate ones) are null
     * rather than empty when it holds none, so a view reads the same as it did before any of them
     * existed.
     */
    static ViewContentsDto read(IArchimateDiagramModel view) {
        ViewContentsCollector collector = new ViewContentsCollector();
        collector.collectViewContents(view, null);

        String viewpoint = view.getViewpoint();
        if (viewpoint != null && viewpoint.isEmpty()) {
            viewpoint = null;
        }
        return new ViewContentsDto(
                view.getId(),
                view.getName(),
                viewpoint,
                DtoMapper.mapConnectionRouterType(view.getConnectionRouterType()),
                collector.elements,
                collector.relationships,
                collector.visualMetadata,
                collector.connections,
                orNull(collector.groups),
                orNull(collector.notes),
                orNull(collector.images),
                orNull(collector.viewReferences),
                orNull(collector.nonArchimateConnections));
    }

    private static <T> List<T> orNull(List<T> rows) {
        return rows.isEmpty() ? null : rows;
    }

    private void collectViewContents(IDiagramModelContainer container, String parentViewObjectId) {
        for (IDiagramModelObject child : container.getChildren()) {
            // Every connection drawn from this object, before anything it holds: the order the
            // clone walk copies them in.
            collectConnections(child);

            if (child instanceof IDiagramModelArchimateObject archimateObject) {
                IArchimateElement element = archimateObject.getArchimateElement();
                if (element != null) {
                    // Dedup elements (same element can appear multiple times in a view)
                    if (seenElementIds.add(element.getId())) {
                        elements.add(DtoMapper.convertToElementDto(element));
                    }

                    // Visual metadata always collected (different positions are meaningful)
                    IBounds bounds = archimateObject.getBounds();
                    if (bounds != null) {
                        visualMetadata.add(new ViewNodeDto(
                                archimateObject.getId(),
                                element.getId(),
                                bounds.getX(),
                                bounds.getY(),
                                bounds.getWidth(),
                                bounds.getHeight(),
                                parentViewObjectId,
                                StylingHelper.readFillColor(archimateObject),
                                StylingHelper.readLineColor(archimateObject),
                                StylingHelper.readFontColor(archimateObject),
                                StylingHelper.readOpacity(archimateObject),
                                StylingHelper.readLineWidth(archimateObject),
                                ImageHelper.readImagePath(archimateObject),
                                ImageHelper.readImagePosition(archimateObject),
                                ImageHelper.readShowIcon(archimateObject),
                                StylingHelper.readFigureType(archimateObject),
                                StylingHelper.readTextAlignment(archimateObject),
                                StylingHelper.readVerticalTextAlignment(archimateObject),
                                StylingHelper.readLabelExpression(archimateObject),
                                StylingHelper.readFontName(archimateObject),
                                StylingHelper.readFontSize(archimateObject),
                                StylingHelper.readFontStyle(archimateObject),
                                StylingHelper.readGradient(archimateObject),
                                StylingHelper.readDeriveLineColor(archimateObject),
                                StylingHelper.readOutlineOpacity(archimateObject),
                                StylingHelper.readLineStyle(archimateObject)));
                    }
                }
            }

            // Collect groups
            if (child instanceof IDiagramModelGroup groupObj) {
                IBounds bounds = groupObj.getBounds();
                List<String> childIds = new ArrayList<>();
                for (IDiagramModelObject groupChild : groupObj.getChildren()) {
                    childIds.add(groupChild.getId());
                }
                groups.add(new ViewGroupDto(
                        groupObj.getId(),
                        groupObj.getName(),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_GROUP_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_GROUP_HEIGHT,
                        parentViewObjectId,
                        childIds.isEmpty() ? null : childIds,
                        StylingHelper.readFillColor(groupObj),
                        StylingHelper.readLineColor(groupObj),
                        StylingHelper.readFontColor(groupObj),
                        StylingHelper.readOpacity(groupObj),
                        StylingHelper.readLineWidth(groupObj),
                        ImageHelper.readImagePath(groupObj),
                        ImageHelper.readImagePosition(groupObj),
                        ImageHelper.readShowIcon(groupObj),
                        StylingHelper.readFigureType(groupObj),
                        StylingHelper.readTextAlignment(groupObj),
                        StylingHelper.readVerticalTextAlignment(groupObj),
                        StylingHelper.readLabelExpression(groupObj),
                        StylingHelper.readFontName(groupObj),
                        StylingHelper.readFontSize(groupObj),
                        StylingHelper.readFontStyle(groupObj),
                        StylingHelper.readGradient(groupObj),
                        StylingHelper.readDeriveLineColor(groupObj),
                        StylingHelper.readOutlineOpacity(groupObj),
                        StylingHelper.readLineStyle(groupObj)));

                // Recurse into group's children
                collectViewContents(groupObj, groupObj.getId());
                continue; // Skip the generic container recursion below
            }

            // Collect notes
            if (child instanceof IDiagramModelNote noteObj) {
                IBounds bounds = noteObj.getBounds();
                notes.add(new ViewNoteDto(
                        noteObj.getId(),
                        noteObj.getContent(),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_NOTE_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_NOTE_HEIGHT,
                        parentViewObjectId,
                        StylingHelper.readFillColor(noteObj),
                        StylingHelper.readLineColor(noteObj),
                        StylingHelper.readFontColor(noteObj),
                        StylingHelper.readOpacity(noteObj),
                        StylingHelper.readLineWidth(noteObj),
                        null, // note field
                        ImageHelper.readImagePath(noteObj),
                        ImageHelper.readImagePosition(noteObj),
                        ImageHelper.readShowIcon(noteObj),
                        StylingHelper.readTextAlignment(noteObj),
                        StylingHelper.readVerticalTextAlignment(noteObj),
                        StylingHelper.readLabelExpression(noteObj),
                        StylingHelper.readFontName(noteObj),
                        StylingHelper.readFontSize(noteObj),
                        StylingHelper.readFontStyle(noteObj),
                        StylingHelper.readGradient(noteObj),
                        StylingHelper.readBorderType(noteObj),
                        StylingHelper.readDeriveLineColor(noteObj),
                        StylingHelper.readOutlineOpacity(noteObj),
                        // A read reports what is on the view, never a placement-time disclosure:
                        // the routes a note was placed among may have moved since.
                        StylingHelper.readLineStyle(noteObj), null));
                continue; // Notes are not containers, no recursion needed
            }

            // Collect IDiagramModelImage visuals (leaf — no recursion).
            // Distinct from IIconic-based imagePath on element/group/note view-objects
            // (which are icon overlays); this is a first-class image node placed
            // directly on the view.
            if (child instanceof IDiagramModelImage imageObj) {
                IBounds bounds = imageObj.getBounds();
                images.add(ImageHelper.imageRow(
                        imageObj,
                        ImageHelper.readPicturePath(imageObj),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_IMAGE_VISUAL_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_IMAGE_VISUAL_HEIGHT,
                        parentViewObjectId,
                        null));
                continue; // Image visuals are leaves (no children to recurse into)
            }

            // A view reference: a leaf, read through the same mapper the add tool answers with.
            if (child instanceof IDiagramModelReference reference) {
                viewReferences.add(DtoMapper.toEmbeddedViewDto(reference, parentViewObjectId));
                continue;
            }

            // Recurse into nested containers (element-as-container)
            // Pass child's view object ID as parent so nested children report correct parentViewObjectId
            if (child instanceof IDiagramModelContainer nestedContainer) {
                collectViewContents(nestedContainer, child.getId());
            }
        }
    }

    /**
     * The connections whose source is {@code end}, each followed by the connections whose source is it:
     * Archi lets a connection start on another connection, and such a connection is held by that one,
     * not by any object. ArchiMate connections go to {@code connections} with their relationship;
     * every other connection goes to {@code nonArchimateConnections}.
     *
     * <p>An ArchiMate connection whose relationship is missing goes to {@code nonArchimateConnections}
     * too. Only a damaged model holds one, but Archi still draws it, and that array is defined as the
     * connections with no relationship behind them, which this one is. Skipping it would leave a drawn
     * line in neither array while the connections drawn from it were listed.</p>
     */
    private void collectConnections(IConnectable end) {
        for (IDiagramModelConnection conn : end.getSourceConnections()) {
            IArchimateRelationship rel = conn instanceof IDiagramModelArchimateConnection a
                    ? a.getArchimateRelationship() : null;
            if (rel != null) {
                IDiagramModelArchimateConnection archimateConn = (IDiagramModelArchimateConnection) conn;
                // RelationshipDto: deduplicated by relationship ID
                if (seenRelationshipIds.add(rel.getId())) {
                    relationships.add(DtoMapper.convertToRelationshipDto(rel, false));
                }
                // ViewConnectionDto: every visual connection collected (each has unique ID).
                // Geometry from the shared builder, styling overlaid — this path used to
                // re-implement both, which is how the two could describe the same connection
                // differently. An end that is another connection has no ArchiMate figure, so
                // both ends' anchors and render faces, and the absolute bendpoints, come out
                // omitted. The element end's face and the absolute bendpoints depend on the
                // remote connection figure's drawn bounds, and those are not in the model.
                connections.add(ConnectionResponseBuilder.withConnectionStyling(
                        ConnectionResponseBuilder.buildConnectionResponseDto(
                                archimateConn.getId(), rel,
                                archimateConn.getSource().getId(),
                                archimateConn.getTarget().getId(),
                                ConnectionResponseBuilder.collectBendpoints(archimateConn),
                                ConnectionResponseBuilder.archimateEndpoint(archimateConn.getSource()),
                                ConnectionResponseBuilder.archimateEndpoint(archimateConn.getTarget()),
                                archimateConn.getTextPosition()),
                        archimateConn));
            } else {
                nonArchimateConnections.add(NonArchimateConnectionMapper.toDto(conn));
            }
            collectConnections(conn);
        }
    }
}
