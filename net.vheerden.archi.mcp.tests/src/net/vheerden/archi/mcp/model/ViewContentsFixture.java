package net.vheerden.archi.mcp.model;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * A real accessor over an in-memory model, a real dispatcher on a real GEF command stack, a stand-in
 * archive holding one image's bytes, and the real {@code get-view-contents} handler over that accessor.
 *
 * <p>The view-contents pins read through both ends with it: the accessor's DTO for literal assertions
 * against the model, and the handler's served JSON for what an agent receives in each format. Shapes
 * this server cannot draw (plain connections, connections on connections, dangling references) are
 * built through EMF by the helpers, as Archi's editor or a script would.</p>
 */
final class ViewContentsFixture {

    static final String SESSION = "view-contents-session";
    static final String IMAGE_PATH = "images/logo.png";

    final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    final IArchimateModel model;
    final IArchimateDiagramModel view;
    final IArchimateDiagramModel other;
    final MutationDispatcher dispatcher;
    final ArchiModelAccessorImpl accessor;
    final CommandStack stack;
    /** Whether the server requires approval; off unless a pin turns it on. */
    boolean approval;
    private final CommandRegistry registry = new CommandRegistry();
    private final ResponseFormatter formatter = new ResponseFormatter();

    ViewContentsFixture(String modelId) throws Exception {
        model = factory.createArchimateModel();
        model.setId(modelId);
        model.setDefaults();
        view = view("view-source", "Source");
        other = view("view-other", "Other");

        byte[] png = png();
        model.setAdapter(IArchiveManager.class, Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IArchiveManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBytesFromEntry")) {
                        return IMAGE_PATH.equals(args[0]) ? png : null;
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }));

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> approval);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
        new ViewHandler(accessor, formatter, registry, null).registerTools();
        new ViewPlacementHandler(accessor, formatter, registry, null).registerTools();
    }

    void dispose() {
        accessor.dispose();
    }

    /**
     * The served {@code get-view-contents} text for {@code args} (the fixture's view unless a
     * {@code viewId} is given), with the two values that differ from run to run masked: the call's
     * duration and the model version.
     */
    String serve(Map<String, Object> args) {
        Map<String, Object> full = new HashMap<>(args);
        full.putIfAbsent("viewId", view.getId());
        return serve("get-view-contents", full);
    }

    /** The served text of any tool the two view handlers register, masked as {@link #serve(Map)}. */
    String serve(String toolName, Map<String, Object> args) {
        McpSchema.CallToolResult result = spec(toolName).callHandler().apply(null,
                new McpSchema.CallToolRequest(toolName, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return (Boolean.TRUE.equals(result.isError()) ? "ERROR " : "")
                + text.replaceAll(",\"durationMs\":[0-9]+", "")
                        .replaceAll("\"modelVersion\":\"[^\"]*\"", "\"modelVersion\":\"_\"");
    }

    McpSchema.Tool tool(String toolName) {
        return spec(toolName).tool();
    }

    private McpServerFeatures.SyncToolSpecification spec(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName)).findFirst().orElseThrow();
    }

    IArchimateDiagramModel view(String id, String name) {
        IArchimateDiagramModel v = factory.createArchimateDiagramModel();
        v.setId(id);
        v.setName(name);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(v);
        return v;
    }

    IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        el.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    IArchimateRelationship relationship(String id, IArchimateConcept source, IArchimateConcept target) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(source);
        rel.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id, IArchimateElement el,
            int x, int y, int width, int height) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, width, height);
        parent.getChildren().add(obj);
        return obj;
    }

    IDiagramModelGroup group(IDiagramModelContainer parent, String id, int x, int y, int width, int height) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(id);
        group.setBounds(x, y, width, height);
        parent.getChildren().add(group);
        return group;
    }

    IDiagramModelNote note(IDiagramModelContainer parent, String id, int x, int y) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId(id);
        note.setContent(id);
        note.setBounds(x, y, 100, 50);
        parent.getChildren().add(note);
        return note;
    }

    IDiagramModelImage image(IDiagramModelContainer parent, String id, int x, int y) {
        IDiagramModelImage image = factory.createDiagramModelImage();
        image.setId(id);
        image.setImagePath(IMAGE_PATH);
        image.setBounds(x, y, 64, 48);
        parent.getChildren().add(image);
        return image;
    }

    IDiagramModelReference reference(IDiagramModelContainer parent, String id,
            com.archimatetool.model.IDiagramModel to, int x, int y) {
        IDiagramModelReference reference = factory.createDiagramModelReference();
        reference.setId(id);
        reference.setReferencedModel(to);
        reference.setBounds(x, y, 120, 55);
        parent.getChildren().add(reference);
        return reference;
    }

    IDiagramModelArchimateConnection archimateConnection(String id, IArchimateRelationship rel,
            IConnectable source, IConnectable target) {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(source, target);
        return conn;
    }

    IDiagramModelConnection plainConnection(String id, IConnectable source, IConnectable target) {
        IDiagramModelConnection conn = factory.createDiagramModelConnection();
        conn.setId(id);
        conn.connect(source, target);
        return conn;
    }

    IDiagramModelBendpoint bendpoint(int startX, int startY, int endX, int endY) {
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX(startX);
        bp.setStartY(startY);
        bp.setEndX(endX);
        bp.setEndY(endY);
        return bp;
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png",
                bytes);
        return bytes.toByteArray();
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(toPlainCompound(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }
}
