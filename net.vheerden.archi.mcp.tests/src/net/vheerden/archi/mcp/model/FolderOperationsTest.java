package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;

/**
 * Characterization pin for {@link FolderOperations} — the static folder-navigation,
 * search, and DTO-assembly helpers, plus the read-facade entry points that take a
 * captured {@code IArchimateModel}.
 *
 * <p>Pure functions over a bare EMF model built with {@code IArchimateFactory.eINSTANCE};
 * runs without OSGi / a loaded model. These assertions lock the current golden output and
 * must stay green, unchanged, across the read-facade completion move.</p>
 *
 * <p>Model shape: two root folders {@code Business} (-> {@code Sub} -> {@code GC}) and
 * {@code Application}.</p>
 */
public class FolderOperationsTest {

    private static final IArchimateFactory F = IArchimateFactory.eINSTANCE;

    private IArchimateModel model;
    private FolderDto bizDto;
    private FolderDto subDto;
    private FolderDto gcDto;
    private FolderDto appDto;

    private static IFolder folder(String id, String name, FolderType type) {
        IFolder f = F.createFolder();
        f.setId(id);
        f.setName(name);
        f.setType(type);
        return f;
    }

    @Before
    public void setUp() {
        IFolder biz = folder("f-biz", "Business", FolderType.BUSINESS);
        IFolder sub = folder("f-sub", "Sub", FolderType.BUSINESS);
        IFolder gc = folder("f-gc", "GC", FolderType.BUSINESS);
        IFolder app = folder("f-app", "Application", FolderType.APPLICATION);
        biz.getFolders().add(sub);
        sub.getFolders().add(gc);

        model = F.createArchimateModel();
        model.getFolders().add(biz);
        model.getFolders().add(app);

        bizDto = new FolderDto("f-biz", "Business", "BUSINESS", "Business", 0, 1);
        subDto = new FolderDto("f-sub", "Sub", "BUSINESS", "Business/Sub", 0, 1);
        gcDto = new FolderDto("f-gc", "GC", "BUSINESS", "Business/Sub/GC", 0, 0);
        appDto = new FolderDto("f-app", "Application", "APPLICATION", "Application", 0, 0);
    }

    // ---- existing static helpers -------------------------------------------------

    @Test
    public void findFolderById_shouldReturnFolder_whenPresentNested() {
        assertEquals("f-gc", FolderOperations.findFolderById(model, "f-gc").getId());
    }

    @Test
    public void findFolderById_shouldReturnNull_whenAbsent() {
        assertNull(FolderOperations.findFolderById(model, "nope"));
    }

    @Test
    public void convertToFolderDto_shouldCarryPathAndCounts() {
        assertEquals(bizDto, FolderOperations.convertToFolderDto(model.getFolders().get(0)));
        IFolder sub = FolderOperations.findFolderById(model, "f-sub");
        assertEquals(subDto, FolderOperations.convertToFolderDto(sub));
    }

    @Test
    public void buildFolderPath_shouldJoinAncestorNames() {
        IFolder gc = FolderOperations.findFolderById(model, "f-gc");
        assertEquals("Business/Sub/GC", FolderOperations.buildFolderPath(gc));
    }

    @Test
    public void buildFolderTree_shouldDescendUnbounded_whenMaxDepthZero() {
        IFolder biz = FolderOperations.findFolderById(model, "f-biz");
        FolderTreeDto tree = FolderOperations.buildFolderTree(biz, 0, 0);
        FolderTreeDto expected = new FolderTreeDto("f-biz", "Business", "BUSINESS", "Business", 0, 1,
                List.of(new FolderTreeDto("f-sub", "Sub", "BUSINESS", "Business/Sub", 0, 1,
                        List.of(new FolderTreeDto("f-gc", "GC", "BUSINESS", "Business/Sub/GC", 0, 0, null)))));
        assertEquals(expected, tree);
    }

    @Test
    public void buildFolderTree_shouldCutAtMaxDepth() {
        IFolder biz = FolderOperations.findFolderById(model, "f-biz");
        FolderTreeDto tree = FolderOperations.buildFolderTree(biz, 1, 0);
        // depth 1: Business -> [Sub], and Sub is not expanded (children null), GC hidden.
        FolderTreeDto expected = new FolderTreeDto("f-biz", "Business", "BUSINESS", "Business", 0, 1,
                List.of(new FolderTreeDto("f-sub", "Sub", "BUSINESS", "Business/Sub", 0, 1, null)));
        assertEquals(expected, tree);
    }

    @Test
    public void collectMatchingFolders_shouldMatchCaseInsensitivelyByName() {
        java.util.List<FolderDto> result = new java.util.ArrayList<>();
        FolderOperations.collectMatchingFolders(model.getFolders().get(0), "sub", result);
        assertEquals(List.of(subDto), result);
    }

    // ---- Read facade: getRootFolders / getFolderById / getFolderChildren / getFolderTree / searchFolders ----

    @Test
    public void getRootFolders_shouldReturnRootDtosInOrder() {
        assertEquals(List.of(bizDto, appDto), FolderOperations.getRootFolders(model));
    }

    @Test
    public void getFolderById_shouldReturnDto_whenPresent() {
        assertEquals(Optional.of(subDto), FolderOperations.getFolderById(model, "f-sub"));
    }

    @Test
    public void getFolderById_shouldReturnEmpty_whenAbsent() {
        assertFalse(FolderOperations.getFolderById(model, "nope").isPresent());
    }

    @Test
    public void getFolderChildren_shouldReturnChildDtos() {
        assertEquals(List.of(subDto), FolderOperations.getFolderChildren(model, "f-biz"));
    }

    @Test
    public void getFolderChildren_shouldReturnEmpty_whenNoChildren() {
        assertTrue(FolderOperations.getFolderChildren(model, "f-gc").isEmpty());
    }

    @Test
    public void getFolderChildren_shouldReturnEmpty_whenParentAbsent() {
        assertTrue(FolderOperations.getFolderChildren(model, "nope").isEmpty());
    }

    @Test
    public void getFolderTree_shouldReturnFullForest_whenRootIdNull() {
        FolderTreeDto bizTree = new FolderTreeDto("f-biz", "Business", "BUSINESS", "Business", 0, 1,
                List.of(new FolderTreeDto("f-sub", "Sub", "BUSINESS", "Business/Sub", 0, 1,
                        List.of(new FolderTreeDto("f-gc", "GC", "BUSINESS", "Business/Sub/GC", 0, 0, null)))));
        FolderTreeDto appTree = new FolderTreeDto("f-app", "Application", "APPLICATION", "Application", 0, 0, null);
        assertEquals(List.of(bizTree, appTree), FolderOperations.getFolderTree(model, null, 0));
    }

    @Test
    public void getFolderTree_shouldReturnSingleRootedTree_whenRootIdGiven() {
        List<FolderTreeDto> trees = FolderOperations.getFolderTree(model, "f-biz", 0);
        assertEquals(1, trees.size());
        assertEquals("f-biz", trees.get(0).id());
    }

    @Test
    public void getFolderTree_shouldReturnEmpty_whenRootIdAbsent() {
        assertTrue(FolderOperations.getFolderTree(model, "nope", 0).isEmpty());
    }

    @Test
    public void searchFolders_shouldMatchCaseInsensitively() {
        assertEquals(List.of(subDto), FolderOperations.searchFolders(model, "sub"));
        assertEquals(List.of(bizDto), FolderOperations.searchFolders(model, "business"));
    }

    @Test
    public void searchFolders_shouldReturnEmpty_whenNoMatch() {
        assertTrue(FolderOperations.searchFolders(model, "zzz").isEmpty());
    }

    // ---- getRootFolder (relocated from the facade; these are the headless replacements) ----

    @Test
    public void getRootFolder_shouldReturnSelf_whenAlreadyRoot() {
        assertEquals("f-biz", FolderOperations.getRootFolder(model.getFolder(FolderType.BUSINESS)).getId());
    }

    @Test
    public void getRootFolder_shouldClimbToRoot_forNestedSubfolders() {
        IFolder sub = model.getFolder(FolderType.BUSINESS).getFolders().get(0);   // Sub
        IFolder gc = sub.getFolders().get(0);                                     // GC
        assertEquals("f-biz", FolderOperations.getRootFolder(sub).getId());
        assertEquals("f-biz", FolderOperations.getRootFolder(gc).getId());
    }

    // ---- hasLayerMismatch (shared decision behind create-element, immediate move, and the
    //      command's execute-time re-check — must reject/accept identically) ----

    @Test
    public void hasLayerMismatch_isFalse_whenConceptFolderMatchesGoverningLayer() {
        IArchimateElement actor = F.createBusinessActor();
        IFolder biz = model.getFolder(FolderType.BUSINESS);
        IFolder sub = biz.getFolders().get(0);
        assertFalse("A Business element under the Business root is correctly filed",
                FolderOperations.hasLayerMismatch(model, actor, biz));
        assertFalse("A Business element under a Business subfolder is correctly filed",
                FolderOperations.hasLayerMismatch(model, actor, sub));
    }

    @Test
    public void hasLayerMismatch_isTrue_whenConceptFolderIsWrongLayer() {
        IArchimateElement actor = F.createBusinessActor();
        IFolder app = model.getFolder(FolderType.APPLICATION);
        assertTrue("A Business element under an Application root is mis-filed",
                FolderOperations.hasLayerMismatch(model, actor, app));
    }

    @Test
    public void hasLayerMismatch_isFalse_whenModelNull() {
        IArchimateElement actor = F.createBusinessActor();
        assertFalse("A null model cannot determine governance and must not falsely reject",
                FolderOperations.hasLayerMismatch(null, actor, model.getFolder(FolderType.APPLICATION)));
    }

    // ---- isViewTargetOutsideDiagrams (uses a setDefaults() model so a Views root exists) ----

    @Test
    public void isViewTargetOutsideDiagrams_discriminatesInsideVsOutsideViews() {
        IArchimateModel m = F.createArchimateModel();
        m.setDefaults();
        IFolder diagrams = m.getFolder(FolderType.DIAGRAMS);
        IFolder businessRoot = m.getFolder(FolderType.BUSINESS);
        IFolder viewsSub = F.createFolder();
        viewsSub.setId("vs");
        viewsSub.setName("vs");
        diagrams.getFolders().add(viewsSub);

        assertFalse("The Views root itself is inside Views",
                FolderOperations.isViewTargetOutsideDiagrams(m, diagrams));
        assertFalse("A subfolder under Views is inside Views",
                FolderOperations.isViewTargetOutsideDiagrams(m, viewsSub));
        assertTrue("A Business-rooted folder is outside Views",
                FolderOperations.isViewTargetOutsideDiagrams(m, businessRoot));
    }
}
