package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.RequireAttachedContainerCommand.Wording;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;

/**
 * Prepares the folder mutations: {@code create-folder}, {@code update-folder} and
 * {@code move-to-folder}. Each prepare validates against the model the call acts on and returns the
 * command with the response it previews; nothing here dispatches.
 *
 * <p>Split out of the accessor, which holds the approval and batch plumbing around these prepares
 * and forwards to them, so the folder rules live in one place beside {@link FolderOperations}.</p>
 */
final class FolderMutationOperations {

    private final Supplier<IArchimateModel> model;
    private final BiConsumer<IArchimateConcept, IFolder> layerCheck;
    private final Function<String, List<Command>> queuedLeaves;

    /**
     * @param model      the model the current call acts on; throws when none is loaded
     * @param layerCheck refuses a concept whose target folder is under the wrong layer root, as
     *                   {@code create-element} does
     */
    FolderMutationOperations(Supplier<IArchimateModel> model,
            BiConsumer<IArchimateConcept, IFolder> layerCheck, Function<String, List<Command>> queuedLeaves) {
        this.model = model;
        this.layerCheck = layerCheck;
        this.queuedLeaves = queuedLeaves;
    }

    /**
     * The operations before this one that can create a folder it names: the session's open batch's
     * queued commands, then {@code sameCall}. Null when there are none.
     */
    List<PreparedMutation<?>> earlier(String sessionId, List<PreparedMutation<?>> sameCall) {
        return earlier(queuedLeaves.apply(sessionId), sameCall);
    }

    /**
     * {@code folderId}, once it is known not to name a folder an earlier operation creates and has not
     * yet committed: that folder cannot be deleted until it has.
     */
    String requireNotMadeEarlier(String sessionId, String folderId, List<PreparedMutation<?>> sameCall) {
        if (madeInCall(folderId, earlier(sessionId, sameCall)) instanceof IFolder made) {
            throw new ModelAccessException("Folder " + CallerText.quoted(made.getName())
                    + " is created by an earlier operation that has not been committed yet, so it cannot be deleted until it has",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Leave out both the create-folder and the delete-folder, or delete it once the call or batch that creates it has committed",
                    null);
        }
        return folderId;
    }

    PreparedMutation<FolderDto> prepareCreateFolder(String parentId, String name,
            String documentation, Map<String, String> properties) {
        return prepareCreateFolder(parentId, name, documentation, properties, null);
    }

    PreparedMutation<FolderDto> prepareUpdateFolder(String id, String name,
            String documentation, Map<String, String> properties) {
        return prepareUpdateFolder(id, name, documentation, properties, null);
    }

    PreparedMutation<MoveResultDto> prepareMoveToFolder(String objectId, String targetFolderId) {
        return prepareMoveToFolder(objectId, targetFolderId, null);
    }

    /** @param sameCall the operations before this one that can create a folder it names, from {@link #earlier}; null when none */
    PreparedMutation<FolderDto> prepareCreateFolder(String parentId, String name,
            String documentation, Map<String, String> properties, List<PreparedMutation<?>> sameCall) {
        IArchimateModel model = this.model.get();

        if (parentId == null || parentId.isBlank()) {
            throw new ModelAccessException(
                    "parentId is required — top-level ArchiMate folders are model-managed",
                    ErrorCode.CANNOT_CREATE_ROOT_FOLDER,
                    null,
                    "Use get-folders to find a parent folder ID to create a subfolder under",
                    null);
        }

        if (name == null || name.isBlank()) {
            throw new ModelAccessException(
                    "Folder name must not be null or blank",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty name for the new folder",
                    null);
        }

        IFolder parentFolder = find(model, parentId, sameCall);
        if (parentFolder == null) {
            throw new ModelAccessException(
                    "Parent folder not found: " + CallerText.bare(parentId),
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid parent folder IDs",
                    null);
        }

        IFolder newFolder = IArchimateFactory.eINSTANCE.createFolder();
        newFolder.setName(InputValidation.reject(name, "name"));
        newFolder.setType(FolderType.USER);

        if (documentation != null) {
            newFolder.setDocumentation(documentation);
        }

        ConceptMetadata.applyProperties(newFolder, properties);

        Command cmd = new RequireAttachedContainerCommand(
                new CreateFolderCommand(newFolder, parentFolder), parentFolder, model, name,
                "folder", Wording.CREATE_IN_FOLDER);

        // Path is built from the name the folder actually carries, not the requested string, so
        // the two cannot disagree if a validator ever normalises what it is handed.
        String parentPath = pathOf(parentFolder, sameCall);
        String newPath = parentPath + "/" + newFolder.getName();
        FolderDto dto = new FolderDto(
                newFolder.getId(), newFolder.getName(),
                newFolder.getType().name(), newPath, 0, 0);

        return new PreparedMutation<>(cmd, dto, newFolder.getId(), newFolder);
    }

    /** @param sameCall as for {@link #prepareCreateFolder} */
    PreparedMutation<FolderDto> prepareUpdateFolder(String id, String name,
            String documentation, Map<String, String> properties, List<PreparedMutation<?>> sameCall) {
        IArchimateModel model = this.model.get();

        if (name == null && documentation == null && properties == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, documentation, properties",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, documentation, or properties to update",
                    null);
        }

        if (name != null && name.isBlank()) {
            throw new ModelAccessException(
                    "Folder name must not be blank",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty name, or omit the name field to leave it unchanged",
                    null);
        }

        IFolder folder = find(model, id, sameCall);
        if (folder == null) {
            throw new ModelAccessException(
                    "Folder not found: " + CallerText.bare(id),
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid folder IDs",
                    null);
        }

        // Validate ONCE and hand the same value to the command and to the response, so the reported
        // name is the name that will be written rather than the name that was asked for.
        String validatedName = InputValidation.reject(name, "name");
        Command cmd = new RequireAttachedContainerCommand(
                new UpdateFolderCommand(folder, validatedName, documentation, properties),
                folder, model, folder.getName(), "folder", Wording.UPDATED);

        // Build the DTO reflecting the proposed new state
        String effectiveName = validatedName != null ? validatedName : folder.getName();
        String effectiveDoc = documentation != null ? documentation : folder.getDocumentation();
        IFolder parent = parentOf(folder, sameCall);
        String path = parent != null ? pathOf(parent, sameCall) + "/" + effectiveName : effectiveName;

        FolderDto dto = new FolderDto(
                folder.getId(), effectiveName,
                folder.getType().name(), path,
                folder.getElements().size(), folder.getFolders().size());

        return new PreparedMutation<>(cmd, dto, folder.getId(), folder);
    }

    /** @param sameCall as for {@link #prepareCreateFolder} */
    PreparedMutation<MoveResultDto> prepareMoveToFolder(String objectId, String targetFolderId,
            List<PreparedMutation<?>> sameCall) {
        IArchimateModel model = this.model.get();

        // Find target folder
        IFolder targetFolder = find(model, targetFolderId, sameCall);
        if (targetFolder == null) {
            throw new ModelAccessException(
                    "Target folder not found: " + CallerText.bare(targetFolderId),
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid target folder IDs",
                    null);
        }

        // Something an earlier operation creates goes where that operation puts it. A move recorded now
        // could not be undone in order: the object is not yet in the folder it would be taken from.
        if (madeInCall(objectId, sameCall) instanceof INameable made) {
            throw new ModelAccessException(
                    CallerText.quoted(made.getName()) + " is created by an earlier operation that has not been committed yet, so it cannot be moved until it has",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Create it in the folder you want (its folderId or parentId), or move it once the call or batch that creates it has committed", null);
        }

        // Find the object and its parent
        ObjectAndParent found = findObjectAndParent(model, objectId);
        if (found == null) {
            throw new ModelAccessException(
                    "Object not found: " + CallerText.bare(objectId)
                            + ". The ID must refer to an element, relationship, view, or folder.",
                    ErrorCode.OBJECT_NOT_FOUND,
                    null,
                    "Use search-elements or get-folders to find valid object IDs",
                    null);
        }

        EObject object = found.object;
        boolean isFolder = found.isFolder;
        // The folder the object is in when this move runs: where an earlier move in the call puts it, else
        // where it is now, so a move back to where it started is not "already in the target", and the
        // response names the folder it leaves. That folder may be one the call has not committed yet. The
        // command itself records where the object is when it runs, which undo returns it to.
        IFolder movedTo = movedInCall(object, sameCall);
        IFolder sourceFolder = movedTo != null ? movedTo : found.parentFolder;

        // Reject moving default top-level folders (parentFolder is null for these)
        if (isFolder && found.parentFolder == null) {
            throw new ModelAccessException(
                    "Cannot move default ArchiMate folder: " + ((IFolder) object).getName(),
                    ErrorCode.CANNOT_MOVE_DEFAULT_FOLDER,
                    null,
                    "Default ArchiMate layer folders cannot be moved. "
                            + "Only user-created subfolders can be moved.",
                    null);
        }

        // Check already in target
        if (sourceFolder.getId().equals(targetFolder.getId())) {
            throw new ModelAccessException(
                    "Object is already in the target folder: " + FolderOperations.buildFolderPath(targetFolder),
                    ErrorCode.ALREADY_IN_TARGET_FOLDER,
                    null,
                    "The object is already a direct child of the specified folder",
                    null);
        }

        // Check circular folder reference
        if (isFolder && descendsFrom(targetFolder, (IFolder) object, sameCall)) {
            throw new ModelAccessException(
                    "Cannot move folder into its own subtree — this would create a circular reference",
                    ErrorCode.CIRCULAR_FOLDER_REFERENCE,
                    null,
                    "Choose a target folder that is not a descendant of the folder being moved",
                    null);
        }

        // Views can only live within the Views (DIAGRAMS) hierarchy. IDiagramModel (not just
        // IArchimateDiagramModel) so sketch and canvas views — which Archi also governs under the
        // Views root — are covered, matching host Archi's save-time checkIntegrity.
        if (object instanceof IDiagramModel
                && FolderOperations.isViewTargetOutsideDiagrams(model, inModel(targetFolder, sameCall))) {
            throw new ModelAccessException(
                    "Views can only be moved within the Views folder hierarchy",
                    ErrorCode.INVALID_MOVE_TARGET,
                    null,
                    "Choose a target folder that is within the Views hierarchy. "
                            + "Use get-folders to find folders under the Views root.",
                    null);
        }

        // Concepts (elements + relationships) must live under their governing layer folder —
        // mirror the create-element guard so create and move reject identically (Archi's
        // save-time checkIntegrity would otherwise refuse to save a mis-filed concept).
        if (object instanceof IArchimateConcept concept) {
            checkLayer(concept, targetFolder, sameCall);
        }

        // Determine source index for undo
        int sourceIndex;
        if (isFolder) {
            sourceIndex = found.parentFolder.getFolders().indexOf((IFolder) object);
        } else {
            sourceIndex = found.parentFolder.getElements().indexOf(object);
        }

        Command cmd = new MoveToFolderCommand(object, isFolder, found.parentFolder, sourceIndex,
                targetFolder);

        // Build result DTO
        String objectName;
        String objectType;
        // eClass for every kind moved, not only elements — objectType below stays the coarse noun.
        String elementType = (object instanceof EObject eo) ? eo.eClass().getName() : null;

        if (isFolder) {
            objectName = ((IFolder) object).getName();
            objectType = "Folder";
        } else if (object instanceof IArchimateElement element) {
            objectName = element.getName();
            objectType = "Element";
        } else if (object instanceof IArchimateRelationship rel) {
            objectName = rel.getName() != null ? rel.getName() : rel.eClass().getName();
            objectType = "Relationship";
        } else if (object instanceof IDiagramModel view) {
            objectName = view.getName();
            objectType = "View";
        } else {
            objectName = objectId;
            objectType = "Unknown";
        }

        MoveResultDto dto = new MoveResultDto(
                objectId, objectName, objectType, elementType,
                pathOf(sourceFolder, sameCall), pathOf(targetFolder, sameCall));

        return new PreparedMutation<>(cmd, dto, objectId);
    }

    // ---- folders the request has not committed yet ----
    //
    // A folder an earlier create-folder made — in the same bulk call, or queued in the open batch — is
    // not in the model until that commits: it has an id and a name, and no container. Anything that
    // walks up from it (its path, its layer root, whether it sits under the Views root) would answer
    // for a folder with no parent. These read the earlier operations' own record of where it goes
    // instead, from the list earlier() builds. For a folder in the model each answers exactly what the
    // model does, so a call with no earlier operations (sameCall null) is unchanged.

    /**
     * The operations before this one that can create a folder it names: the open batch's queued
     * commands first, in queue order, then the enclosing bulk call's own. Null when there are none.
     *
     * @param queuedLeaves the open batch's queued leaf commands, or null outside a batch
     * @param sameCall     the operations an enclosing bulk call prepared before this one, or null
     */
    static List<PreparedMutation<?>> earlier(List<Command> queuedLeaves, List<PreparedMutation<?>> sameCall) {
        if (queuedLeaves == null || queuedLeaves.isEmpty()) {
            return sameCall;
        }
        List<PreparedMutation<?>> earlier = new ArrayList<>();
        for (Command leaf : queuedLeaves) {
            earlier.add(new PreparedMutation<>(leaf, null, null,
                    leaf instanceof CreateFolderCommand create ? create.getNewFolder() : null));
        }
        if (sameCall != null) {
            earlier.addAll(sameCall);
        }
        return earlier;
    }

    /**
     * The folder with this id: one an earlier operation creates, else the model's.
     *
     * @return the folder, or null when neither has it
     */
    static IFolder find(IArchimateModel model, String id, List<PreparedMutation<?>> sameCall) {
        return madeInCall(id, sameCall) instanceof IFolder created ? created
                : FolderOperations.findFolderById(model, id);
    }

    /**
     * The object with this id that an earlier operation creates and has not yet added to the model, or
     * null.
     */
    static EObject madeInCall(String id, List<PreparedMutation<?>> sameCall) {
        if (id == null || sameCall == null) {
            return null;
        }
        for (PreparedMutation<?> earlier : sameCall) {
            if (earlier.rawObject() instanceof IIdentifier made && id.equals(made.getId())
                    && ((EObject) made).eContainer() == null) {
                return (EObject) made;
            }
        }
        return null;
    }

    /**
     * The parent earlier operations put {@code folder} in, or null when none does: the parent an earlier
     * create-folder makes it in, then the target of each earlier move-to-folder of it, the last move
     * winning, as the last rename does in {@link #nameAfter}. A folder already in the model is moved
     * only by a move.
     */
    static IFolder parentInCall(IFolder folder, List<PreparedMutation<?>> sameCall) {
        if (sameCall == null) {
            return null;
        }
        boolean created = folder.eContainer() == null;
        IFolder[] parent = { null };
        for (PreparedMutation<?> earlier : sameCall) {
            MutationContext.forEachCommand(earlier.command(), command -> {
                if (created && command instanceof CreateFolderCommand create && create.getNewFolder() == folder) {
                    parent[0] = create.getParentFolder();
                }
            });
        }
        IFolder moved = movedInCall(folder, sameCall);
        return moved != null ? moved : parent[0];
    }

    /**
     * The folder the last earlier move-to-folder of {@code object} puts it in, or null when none moves it.
     * A later move wins.
     */
    static IFolder movedInCall(EObject object, List<PreparedMutation<?>> sameCall) {
        if (sameCall == null) {
            return null;
        }
        IFolder[] target = { null };
        for (PreparedMutation<?> earlier : sameCall) {
            MutationContext.forEachCommand(earlier.command(), command -> {
                if (command instanceof MoveToFolderCommand move && move.getObject() == object) {
                    target[0] = move.getTargetFolder();
                }
            });
        }
        return target[0];
    }

    /**
     * The path {@code folder} has once the call commits: the model's for a folder in it; for one this
     * call creates, its parent's path and the name the call leaves it with.
     */
    static String pathOf(IFolder folder, List<PreparedMutation<?>> sameCall) {
        if (sameCall == null) {
            return FolderOperations.buildFolderPath(folder);
        }
        IFolder parent = parentOf(folder, sameCall);
        String name = nameAfter(folder, sameCall);
        return parent == null ? name : pathOf(parent, sameCall) + "/" + name;
    }

    /** The folder {@code folder} is, or will be, inside: the parent earlier operations give it, else its own. */
    private static IFolder parentOf(IFolder folder, List<PreparedMutation<?>> sameCall) {
        IFolder parent = parentInCall(folder, sameCall);
        return parent != null ? parent : folder.eContainer() instanceof IFolder live ? live : null;
    }

    /** The name the last earlier rename gives {@code folder}, of any folder in or out of the model, else its own. */
    private static String nameAfter(IFolder folder, List<PreparedMutation<?>> sameCall) {
        String[] name = { folder.getName() };
        for (PreparedMutation<?> earlier : sameCall) {
            MutationContext.forEachCommand(earlier.command(), command -> {
                if (command instanceof UpdateFolderCommand update && update.getFolder() == folder
                        && update.getNewName() != null) {
                    name[0] = update.getNewName(); // a later rename wins
                }
            });
        }
        return name[0];
    }

    /**
     * Refuses {@code concept} in {@code folder} when the folder is, or will be, under another layer's root.
     * The layer is judged by the nearest folder already in the model; the refusal names {@code folder}.
     */
    void checkLayer(IArchimateConcept concept, IFolder folder, List<PreparedMutation<?>> sameCall) {
        IFolder governing = inModel(folder, sameCall);
        try {
            layerCheck.accept(concept, governing);
        } catch (ModelAccessException refused) {
            String judged = "the target folder '" + governing.getName() + "'";
            if (governing == folder || !refused.getMessage().contains(judged)) {
                throw refused;
            }
            throw new ModelAccessException(refused.getMessage().replace(judged,
                    "the target folder '" + folder.getName() + "'"), refused.getErrorCode(), refused.getDetails(),
                    refused.getSuggestedCorrection(), refused.getArchiMateReference());
        }
    }

    /**
     * The nearest folder in the model that {@code folder} is, or will be, inside: itself when it is in
     * the model. Which layer root and which tree a folder an earlier operation creates belongs to is decided by it.
     */
    static IFolder inModel(IFolder folder, List<PreparedMutation<?>> sameCall) {
        IFolder parent = parentInCall(folder, sameCall);
        return parent == null ? folder : inModel(parent, sameCall);
    }

    /** As {@link FolderOperations#isOrDescendsFrom}, through the parents earlier operations give. */
    private static boolean descendsFrom(IFolder folder, IFolder ancestor, List<PreparedMutation<?>> sameCall) {
        IFolder parent = parentInCall(folder, sameCall);
        return folder == ancestor || (parent != null ? descendsFrom(parent, ancestor, sameCall)
                : FolderOperations.isOrDescendsFrom(folder, ancestor));
    }

    /**
     * Finds an object by ID across all model folders and determines its parent
     * folder and whether it is itself a folder.
     *
     * <p>Searches elements (including relationships stored in element lists),
     * views, and subfolders across the entire model hierarchy. Uses
     * {@code ArchimateModelUtils.getObjectByID()} for elements/relationships/views,
     * then uses {@code eContainer()} to determine the parent folder.</p>
     *
     * @return ObjectAndParent if found, null otherwise
     */
    private static ObjectAndParent findObjectAndParent(IArchimateModel model, String objectId) {
        // Try as a folder first
        IFolder asFolder = FolderOperations.findFolderById(model, objectId);
        if (asFolder != null) {
            EObject container = asFolder.eContainer();
            if (container instanceof IFolder parentFolder) {
                return new ObjectAndParent(asFolder, parentFolder, true);
            }
            // Top-level folder — parent is model, not a folder
            // We still return it; the caller will reject moving default folders
            return new ObjectAndParent(asFolder, null, true);
        }

        // Try as element, relationship, or view via ArchimateModelUtils
        EObject obj = ArchimateModelUtils.getObjectByID(model, objectId);
        if (obj != null) {
            EObject container = obj.eContainer();
            if (container instanceof IFolder parentFolder) {
                return new ObjectAndParent(obj, parentFolder, false);
            }
        }

        return null;
    }

    /**
     * Result of finding an object and its parent folder.
     * Package-visible for testing.
     */
    static final class ObjectAndParent {
        final EObject object;
        final IFolder parentFolder;
        final boolean isFolder;

        ObjectAndParent(EObject object, IFolder parentFolder, boolean isFolder) {
            this.object = object;
            this.parentFolder = parentFolder;
            this.isFolder = isFolder;
        }
    }
}
