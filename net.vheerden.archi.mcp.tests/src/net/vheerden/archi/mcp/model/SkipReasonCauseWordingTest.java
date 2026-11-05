package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProfile;

/**
 * The reason a command gives when it declines names the change as having happened after the command
 * was prepared, by another operation in the request or by an edit in Archi, rather than blaming an
 * earlier operation that a single call or an approval does not have. The rest of each reason is
 * unchanged.
 */
public class SkipReasonCauseWordingTest {

    private static final String CAUSE =
            "after this change was prepared (by an earlier operation in the same request, or an edit in Archi)";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IFolder business;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
    }

    @Test
    public void shouldNameTheCause_whenACreateFolderWentAway() {
        IFolder target = folder(business, "Target");
        RequireAttachedContainerCommand guard = new RequireAttachedContainerCommand(noop(), target, model,
                "Actor", "folder", RequireAttachedContainerCommand.Wording.CREATE_IN_FOLDER);
        business.getFolders().remove(target);

        assertReason(declined(guard), "was removed from the model " + CAUSE + ". Anything created there",
                "'Actor' was not created: the folder 'Target'", "Create it in a separate request");
    }

    @Test
    public void shouldNameTheCause_whenAnAddToContainerWentAway() {
        IFolder target = folder(business, "Target");
        RequireAttachedContainerCommand guard = new RequireAttachedContainerCommand(noop(), target, model,
                "Box", "group", RequireAttachedContainerCommand.Wording.ADD_TO_CONTAINER);
        business.getFolders().remove(target);

        assertReason(declined(guard), "was removed from the model " + CAUSE + ". Anything added there",
                "'Box' was not added to the view: the group 'Target'", "Add it in a separate request");
    }

    @Test
    public void shouldNameTheCause_whenAConnectionEndWentAway() {
        IFolder target = folder(business, "End");
        RequireAttachedContainerCommand guard = new RequireAttachedContainerCommand(noop(), target, model,
                "Link", "view object", RequireAttachedContainerCommand.Wording.CONNECT_ENDPOINT);
        business.getFolders().remove(target);

        assertReason(declined(guard), "it connects was removed from the model " + CAUSE + ". A connection",
                "'Link' was not added to the view: the view object 'End'",
                "connect view objects the request does not remove.");
    }

    @Test
    public void shouldNameTheCause_whenAFolderMoveWouldFormACycle() {
        IFolder moving = folder(business, "A");
        IFolder target = folder(business, "B");
        MoveToFolderCommand move = new MoveToFolderCommand(moving, true, business,
                business.getFolders().indexOf(moving), target);
        moving.getFolders().add(target);

        assertReason(declined(move), "because the folders were re-parented " + CAUSE + ". Completing",
                "Folder 'A' was not moved into 'B'", "so it was left in place and nothing is lost.");
    }

    @Test
    public void shouldNameTheCause_whenAViewTargetLeftTheViewsFolder() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        views.getElements().add(view);
        IFolder target = folder(views, "T");
        MoveToFolderCommand move = new MoveToFolderCommand(view, false, views,
                views.getElements().indexOf(view), target);
        business.getFolders().add(target);

        assertReason(declined(move), "because it was re-parented " + CAUSE + ". A view filed",
                "View 'V' was not moved into 'T'", "so it was left in place.");
    }

    @Test
    public void shouldNameTheCause_whenAConceptTargetChangedLayer() {
        IArchimateElement actor = actor(business, "E");
        IFolder target = folder(business, "T");
        MoveToFolderCommand move = new MoveToFolderCommand(actor, false, business,
                business.getElements().indexOf(actor), target);
        model.getFolder(FolderType.APPLICATION).getFolders().add(target);

        assertReason(declined(move), "because it was re-parented " + CAUSE + ". A concept filed",
                "'E' was not moved into 'T'", "was left in place.");
    }

    @Test
    public void shouldNameTheCause_whenAConceptGainedAnUnauthorisedProfile() {
        IArchimateElement actor = actor(business, "C");
        IProfile vip = profile("VIP");
        actor.getProfiles().add(vip);
        ClearSpecializationCommand clear = new ClearSpecializationCommand(actor, Set.of(vip));
        actor.getProfiles().add(profile("Other"));

        assertReason(declined(clear), "— it was attached " + CAUSE + ". Detach",
                "Concept 'C' was left untouched because it now also carries 'Other'",
                "Detach or delete the other specialization(s) explicitly, then retry the delete.");
    }

    @Test
    public void shouldNameTheCause_whenAProfileGainedAUsage() {
        IProfile vip = profile("VIP");
        DeleteProfileCommand delete = new DeleteProfileCommand(vip, model, false);
        actor(business, "C").getProfiles().add(vip);

        assertReason(declined(delete), "— it was attached " + CAUSE + ". Delete it",
                "Specialization 'VIP' was still applied to 1 concept when the changes were applied",
                "Delete it in a separate request, or use force: true");
    }

    @Test
    public void shouldNameTheCause_whenAFolderGainedContent() {
        IFolder ops = folder(business, "ops");
        DeleteFolderCommand delete = new DeleteFolderCommand(ops, business, business.getFolders().indexOf(ops),
                List.of());
        actor(ops, "late");

        assertReason(declined(delete), "1 item(s) were added to it " + CAUSE + ". The folder",
                "Folder 'ops' was not empty when the changes were applied",
                "The folder and its contents were left in place so nothing is lost.");
    }

    @Test
    public void shouldNameTheCause_whenTheCloneSourceWentAway() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = factory.createArchimateDiagramModel();
        source.setName("S");
        views.getElements().add(source);
        IArchimateDiagramModel clone = factory.createArchimateDiagramModel();
        clone.setName("C");
        views.getElements().add(clone);
        CloneViewContentsCommand copy = new CloneViewContentsCommand(source, clone, model);
        views.getElements().remove(source);

        assertReason(declined(copy), "was removed from the model " + CAUSE + ", so there was nothing left",
                "'C' was not created: the view 'S' it was to be copied from",
                "Clone it before the operation that removes it, or in a separate request.");
    }

    private static String declined(Command command) {
        command.execute();
        String reason = ((CommitSkippableCommand) command).getSkipReason();
        assertNotNull("fixture check: the command declined", reason);
        return reason;
    }

    private static void assertReason(String reason, String cause, String subject, String remedy) {
        assertTrue("cause: " + reason, reason.contains(cause));
        assertTrue("subject: " + reason, reason.contains(subject));
        assertTrue("remedy: " + reason, reason.contains(remedy));
    }

    private static Command noop() {
        return new Command("Guarded") { };
    }

    private IFolder folder(IFolder parent, String name) {
        IFolder folder = factory.createFolder();
        folder.setName(name);
        parent.getFolders().add(folder);
        return folder;
    }

    private IArchimateElement actor(IFolder folder, String name) {
        IArchimateElement element = factory.createBusinessActor();
        element.setName(name);
        folder.getElements().add(element);
        return element;
    }

    private IProfile profile(String name) {
        IProfile profile = factory.createProfile();
        profile.setName(name);
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        return profile;
    }
}
