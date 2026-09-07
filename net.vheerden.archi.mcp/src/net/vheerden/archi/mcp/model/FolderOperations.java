package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;

/**
 * Folder navigation, search, and DTO conversion helpers.
 *
 * <p>Extracted from ArchiModelAccessorImpl to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class FolderOperations {

    private FolderOperations() {}

    // ---- Read facade: folder navigation/search assembly over a captured model ----

    static List<FolderDto> getRootFolders(IArchimateModel model) {
        List<FolderDto> result = new ArrayList<>();
        for (IFolder folder : model.getFolders()) {
            result.add(convertToFolderDto(folder));
        }
        return result;
    }

    static Optional<FolderDto> getFolderById(IArchimateModel model, String id) {
        IFolder found = findFolderById(model, id);
        if (found == null) {
            return Optional.empty();
        }
        return Optional.of(convertToFolderDto(found));
    }

    static List<FolderDto> getFolderChildren(IArchimateModel model, String parentId) {
        IFolder parent = findFolderById(model, parentId);
        if (parent == null) {
            return List.of();
        }
        List<FolderDto> result = new ArrayList<>();
        for (IFolder child : parent.getFolders()) {
            result.add(convertToFolderDto(child));
        }
        return result;
    }

    static List<FolderTreeDto> getFolderTree(IArchimateModel model, String rootId, int maxDepth) {
        if (rootId != null) {
            IFolder root = findFolderById(model, rootId);
            if (root == null) {
                return List.of();
            }
            return List.of(buildFolderTree(root, maxDepth, 0));
        }
        // Full tree: all root folders
        List<FolderTreeDto> result = new ArrayList<>();
        for (IFolder folder : model.getFolders()) {
            result.add(buildFolderTree(folder, maxDepth, 0));
        }
        return result;
    }

    static List<FolderDto> searchFolders(IArchimateModel model, String nameQuery) {
        String lowerQuery = nameQuery.toLowerCase();
        List<FolderDto> result = new ArrayList<>();
        for (IFolder folder : model.getFolders()) {
            collectMatchingFolders(folder, lowerQuery, result);
        }
        return result;
    }

    static IFolder findFolderById(IArchimateModel model, String id) {
        for (IFolder root : model.getFolders()) {
            IFolder found = findFolderByIdRecursive(root, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static IFolder findFolderByIdRecursive(IFolder folder, String id) {
        if (id.equals(folder.getId())) {
            return folder;
        }
        for (IFolder child : folder.getFolders()) {
            IFolder found = findFolderByIdRecursive(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static FolderDto convertToFolderDto(IFolder folder) {
        return new FolderDto(
                folder.getId(),
                folder.getName(),
                folder.getType().name(),
                buildFolderPath(folder),
                folder.getElements().size(),
                folder.getFolders().size());
    }

    static FolderTreeDto buildFolderTree(IFolder folder, int maxDepth, int currentDepth) {
        List<FolderTreeDto> children = null;
        if (maxDepth <= 0 || currentDepth < maxDepth) {
            if (!folder.getFolders().isEmpty()) {
                children = new ArrayList<>();
                for (IFolder child : folder.getFolders()) {
                    children.add(buildFolderTree(child, maxDepth, currentDepth + 1));
                }
            }
        }
        return new FolderTreeDto(
                folder.getId(),
                folder.getName(),
                folder.getType().name(),
                buildFolderPath(folder),
                folder.getElements().size(),
                folder.getFolders().size(),
                children);
    }

    static void collectMatchingFolders(IFolder folder, String lowerQuery, List<FolderDto> result) {
        if (folder.getName() != null && folder.getName().toLowerCase().contains(lowerQuery)) {
            result.add(convertToFolderDto(folder));
        }
        for (IFolder child : folder.getFolders()) {
            collectMatchingFolders(child, lowerQuery, result);
        }
    }

    static String buildFolderPath(IFolder folder) {
        StringBuilder path = new StringBuilder();
        buildFolderPathRecursive(folder, path);
        return path.toString();
    }

    /**
     * Builds the path a folder will have once a pending rename executes.
     *
     * <p>A rename has not been applied yet when the response is built — the command runs later, and
     * later still when the call is queued in a batch — so the path cannot simply be read off the
     * folder. It is rebuilt from the folder's live ancestry plus the name that will actually be
     * written, which is the closest thing to effective state available before execution and, more
     * importantly, is sourced from the same value the command receives rather than from the raw
     * request. Should any name normalisation ever land on the write path, the reported path follows
     * it instead of silently disagreeing with it.</p>
     *
     * @param folder        the folder being renamed
     * @param effectiveName the name the write will actually store
     * @param renaming      false when no rename is pending, in which case the live path is returned
     * @return the folder's path after the pending rename
     */
    static String pathAfterRename(IFolder folder, String effectiveName, boolean renaming) {
        if (!renaming) {
            return buildFolderPath(folder);
        }
        EObject parent = folder.eContainer();
        return (parent instanceof IFolder parentFolder)
                ? buildFolderPath(parentFolder) + "/" + effectiveName
                : effectiveName;
    }

    private static void buildFolderPathRecursive(IFolder folder, StringBuilder path) {
        EObject parent = folder.eContainer();
        if (parent instanceof IFolder parentFolder) {
            buildFolderPathRecursive(parentFolder, path);
            path.append('/');
        }
        path.append(folder.getName());
    }

    /**
     * Returns true if {@code folder} is the same as, or descends from, {@code ancestor},
     * walking the live containment chain.
     *
     * <p>Used to detect circular folder moves: a move whose target equals or descends from
     * the folder being moved would create a containment cycle. Because it reads the live
     * {@code eContainer} chain it stays correct when an earlier operation in the same request
     * has already re-parented the folders, which is why the move command re-checks it at
     * execution time and not only at prepare time.</p>
     *
     * @param folder   the folder to test (typically the move-target folder)
     * @param ancestor the folder that might be an ancestor (typically the folder being moved)
     * @return true if {@code folder} equals or descends from {@code ancestor}
     */
    static boolean isOrDescendsFrom(IFolder folder, IFolder ancestor) {
        if (folder.getId().equals(ancestor.getId())) {
            return true;
        }
        EObject current = folder.eContainer();
        while (current instanceof IFolder parentFolder) {
            if (parentFolder.getId().equals(ancestor.getId())) {
                return true;
            }
            current = parentFolder.eContainer();
        }
        return false;
    }

    /**
     * Walks up the folder hierarchy to find the root folder (direct child of the model).
     *
     * <p>Reads the live {@code eContainer} chain, so it reflects any re-parenting an earlier
     * operation in the same request has already applied — which is why the move command re-checks
     * a target folder's layer at execution time and not only at prepare time.</p>
     */
    static IFolder getRootFolder(IFolder folder) {
        IFolder current = folder;
        while (current.eContainer() instanceof IFolder parent) {
            current = parent;
        }
        return current;
    }

    /**
     * Whether moving a view (diagram) into {@code targetFolder} would land it outside the Views
     * (DIAGRAMS) hierarchy, which host Archi's save-time checkIntegrity refuses to save.
     *
     * <p>Reads the live tree, so it stays correct when an earlier operation in the same batch or
     * bulk request has re-parented the target folder out of the Views hierarchy since this move was
     * prepared. Shared by the prepare-time guard and the move command's execution-time re-check so
     * the two never diverge.</p>
     *
     * @return true if a view placed in {@code targetFolder} would be illegally outside Views
     */
    static boolean isViewTargetOutsideDiagrams(IArchimateModel model, IFolder targetFolder) {
        IFolder diagramsRoot = model == null ? null : model.getFolder(FolderType.DIAGRAMS);
        return diagramsRoot != null
                && !targetFolder.getId().equals(diagramsRoot.getId())
                && !isOrDescendsFrom(targetFolder, diagramsRoot);
    }

    /**
     * Whether moving {@code concept} into {@code targetFolder} would mis-file it under a folder
     * whose root layer differs from the concept's governing layer, which host Archi's save-time
     * checkIntegrity refuses to save.
     *
     * <p>Delegates to Archi's own type→folder authority ({@code getDefaultFolderForObject}) so this
     * check is never stricter nor more forgiving than host Archi, and reads the live root-folder
     * type so it stays correct when an earlier operation re-rooted the target folder under a
     * different layer since this move was prepared. Shared by the prepare-time guard and the move
     * command's execution-time re-check. Returns false when Archi assigns the object no governing
     * folder (non-ArchiMate / outside layer governance) — such objects are not subject to
     * folder-type checkIntegrity either, so this stays consistent with host Archi.</p>
     *
     * @return true if {@code concept} placed in {@code targetFolder} would be mis-filed by layer
     */
    static boolean hasLayerMismatch(IArchimateModel model, IArchimateConcept concept,
            IFolder targetFolder) {
        IFolder defaultFolder = model == null ? null : model.getDefaultFolderForObject(concept);
        if (defaultFolder == null) {
            return false;
        }
        return getRootFolder(targetFolder).getType() != defaultFolder.getType();
    }
}
