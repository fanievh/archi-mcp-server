package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;

/**
 * Folder navigation, search, and DTO conversion helpers.
 *
 * <p>Extracted from ArchiModelAccessorImpl (Story 12-4) to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class FolderOperations {

    private FolderOperations() {}

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

    private static void buildFolderPathRecursive(IFolder folder, StringBuilder path) {
        EObject parent = folder.eContainer();
        if (parent instanceof IFolder parentFolder) {
            buildFolderPathRecursive(parentFolder, path);
            path.append('/');
        }
        path.append(folder.getName());
    }
}
