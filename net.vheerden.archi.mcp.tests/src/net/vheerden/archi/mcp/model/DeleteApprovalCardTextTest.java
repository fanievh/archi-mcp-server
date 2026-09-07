package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Unit-pins {@link DeleteApprovalCardText} — the string/plural/omit logic behind the
 * {@code delete-view} and {@code delete-folder} human approval cards. Pure Java: no EMF,
 * OSGi or SWT, so it runs in the headless CI lane and isolates the card-text contract from
 * the accessor plumbing that carries it (that end-to-end path is pinned in
 * {@code DeleteViewCascadeCountTest} / {@code DeleteFolderEmptyCountTest}, and the rendering of
 * the sentence into a change row in {@code ApprovalCardModelTest}).
 */
public class DeleteApprovalCardTextTest {

    private static DeleteResultDto view(int connections, int references) {
        // relationshipsRemoved is always 0 for a view (it removes no model concepts).
        return new DeleteResultDto("v-1", "Payments", "View",
                0, references, connections, null, null, null);
    }

    private static DeleteResultDto folder(int rel, int vref, int vconn,
            Integer elements, Integer views, Integer folders) {
        return new DeleteResultDto("f-1", "Legacy", "Folder",
                rel, vref, vconn, elements, views, folders);
    }

    // ---- delete-view ----

    @Test
    public void viewDescription_foldsBothCounts_pluralised() {
        assertEquals("Delete view: Payments (cascade: 5 view connections, 3 view references)",
                DeleteApprovalCardText.viewDescription(view(5, 3)));
    }

    @Test
    public void viewDescription_usesSingular_forExactlyOne() {
        assertEquals("Delete view: Payments (cascade: 1 view connection, 1 view reference)",
                DeleteApprovalCardText.viewDescription(view(1, 1)));
    }

    @Test
    public void viewDescription_showsHonestZeros_andNeverMentionsRelationships() {
        String d = DeleteApprovalCardText.viewDescription(view(0, 0));
        assertEquals("Delete view: Payments (cascade: 0 view connections, 0 view references)", d);
        assertFalse("a view card must never mention relationships (always 0)",
                d.toLowerCase().contains("relationship"));
    }

    @Test
    public void putViewCounts_addsBothPrimitives() {
        Map<String, Object> m = new LinkedHashMap<>();
        DeleteApprovalCardText.putViewCounts(m, view(5, 3));
        assertEquals(Integer.valueOf(5), m.get("viewConnectionsRemoved"));
        assertEquals(Integer.valueOf(3), m.get("viewReferencesRemoved"));
    }

    // ---- delete-folder ----

    @Test
    public void folderDescription_nonForce_hasNoCascadeClause() {
        assertEquals("Delete folder: Legacy",
                DeleteApprovalCardText.folderDescription(folder(0, 0, 0, null, null, null), false));
    }

    @Test
    public void folderDescription_force_foldsOnlyNonZeroCounts_inFixedOrder() {
        // elements=2, views=1, subfolders=0(skip), relationships=3, view refs=0(skip), view conns=4
        String d = DeleteApprovalCardText.folderDescription(folder(3, 0, 4, 2, 1, 0), true);
        assertEquals("Delete folder: Legacy (force cascade: 2 elements, 1 view, "
                + "3 relationships, 4 view connections)", d);
    }

    @Test
    public void folderDescription_forceButNothingCascaded_degradesToBareForceCascade() {
        assertEquals("Delete folder: Legacy (force cascade)",
                DeleteApprovalCardText.folderDescription(folder(0, 0, 0, 0, 0, 0), true));
    }

    @Test
    public void putFolderCounts_underForce_addsThreePrimitives() {
        Map<String, Object> m = new LinkedHashMap<>();
        DeleteApprovalCardText.putFolderCounts(m, folder(3, 1, 4, 2, 1, 0), true);
        assertEquals(Integer.valueOf(3), m.get("relationshipsRemoved"));
        assertEquals(Integer.valueOf(1), m.get("viewReferencesRemoved"));
        assertEquals(Integer.valueOf(4), m.get("viewConnectionsRemoved"));
    }

    @Test
    public void putFolderCounts_withoutForce_addsNothing() {
        Map<String, Object> m = new LinkedHashMap<>();
        DeleteApprovalCardText.putFolderCounts(m, folder(0, 0, 0, null, null, null), false);
        assertTrue("a non-force folder delete cascades nothing, so no primitives are surfaced",
                m.isEmpty());
    }

    // ---- cascadeDivergence: does the rebuilt command still do what the card said? ----

    /** The card payload a {@code delete-element} proposal publishes (ArchiModelAccessorImpl). */
    private static Map<String, Object> elementCard(int rel, int vref, int vconn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elementId", "e-1");
        m.put("relationshipsRemoved", rel);
        m.put("viewReferencesRemoved", vref);
        m.put("viewConnectionsRemoved", vconn);
        return m;
    }

    private static DeleteResultDto element(int rel, int vref, int vconn) {
        return new DeleteResultDto("e-1", "Payment Engine", "ApplicationComponent",
                rel, vref, vconn, null, null, null);
    }

    @Test
    public void cascadeDivergence_isNull_whenTheRebuiltCascadeStillMatchesTheCard() {
        assertNull(DeleteApprovalCardText.cascadeDivergence(elementCard(2, 1, 3), element(2, 1, 3)));
    }

    @Test
    public void cascadeDivergence_namesTheGrowth_whenAFolderGainedContents() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("folderId", "f-1");
        card.put("force", true);
        card.put("elementsRemoved", 2);

        assertEquals("This proposal can no longer be applied as reviewed: it was approved for 2 elements, "
                + "but applying it now would remove 5 elements. Reject it and ask the agent to retry.",
                DeleteApprovalCardText.cascadeDivergence(card, folder(0, 0, 0, 5, 0, 0)));
    }

    @Test
    public void cascadeDivergence_coversTheDeleteElementTwin_whenARelationshipWasAttached() {
        // The same shape on delete-element: its tracked ids are the element alone, so a relationship the
        // human attaches during the review window is cascaded but was never counted on the card.
        assertEquals("This proposal can no longer be applied as reviewed: it was approved for "
                + "0 relationships, but applying it now would remove 1 relationship. "
                + "Reject it and ask the agent to retry.",
                DeleteApprovalCardText.cascadeDivergence(elementCard(0, 0, 0), element(1, 0, 0)));
    }

    @Test
    public void cascadeDivergence_listsEveryDivergedCount_inCardOrder() {
        assertEquals("This proposal can no longer be applied as reviewed: it was approved for "
                + "1 relationship, 2 view connections, but applying it now would remove "
                + "4 relationships, 0 view connections. Reject it and ask the agent to retry.",
                DeleteApprovalCardText.cascadeDivergence(elementCard(1, 0, 2), element(4, 0, 0)));
    }

    @Test
    public void cascadeDivergence_ignoresCountsTheCardNeverPublished() {
        // A non-force folder card carries no cascade counts at all, so there is nothing to contradict.
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("folderId", "f-1");
        card.put("force", false);
        assertNull(DeleteApprovalCardText.cascadeDivergence(card, folder(9, 9, 9, 9, 9, 9)));
    }

    @Test
    public void cascadeDivergence_isNull_forANonDeletionProposal() {
        // Every non-delete proposal rebuilds some other entity type; the check must not fire on it.
        assertNull(DeleteApprovalCardText.cascadeDivergence(elementCard(1, 1, 1), "someElementDto"));
        assertNull(DeleteApprovalCardText.cascadeDivergence(null, element(1, 1, 1)));
    }
}
