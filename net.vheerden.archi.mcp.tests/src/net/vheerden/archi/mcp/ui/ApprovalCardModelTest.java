package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.ChangeRow;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.RollupToken;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.State;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.ViewModel;

/**
 * Headless unit tests for {@link ApprovalCardModel}. These cover the
 * decisions the dock view renders: effect-rollup derivation, destructive/amber classification,
 * name-vs-id resolution, delete-hoist ordering, empty/gate-off panel selection, and the
 * bulk-approve selection (safe set + oldest-first ordering + destructive count). No SWT.
 */
public class ApprovalCardModelTest {

    // ---- fixtures ----

    private static PendingProposalView view(String sessionId, String proposalId, String tool,
            String description, Map<String, Object> proposedChanges, String createdAt) {
        ProposalDto dto = new ProposalDto(proposalId, tool, "pending", description,
                null, proposedChanges, "Valid", createdAt);
        return new PendingProposalView(sessionId, dto);
    }

    private static ApprovalCardModel card(String proposalId, String tool, String description,
            Map<String, Object> proposedChanges, String createdAt) {
        return ApprovalCardModel.fromProposal(
                view("default", proposalId, tool, description, proposedChanges, createdAt));
    }

    private static Map<String, Object> bulkOps(int operationCount, String... ops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationCount", operationCount);
        m.put("operations", List.of(ops));
        return m;
    }

    /** A card built from a proposal carrying server {@code effectDescription} + agent {@code intent}. */
    private static ApprovalCardModel cardFull(String proposalId, String tool, String description,
            Map<String, Object> proposedChanges, String createdAt,
            String effectDescription, String intent) {
        ProposalDto dto = new ProposalDto(proposalId, tool, "pending", description,
                null, proposedChanges, "Valid", createdAt, effectDescription, intent);
        return ApprovalCardModel.fromProposal(new PendingProposalView("default", dto));
    }

    /** A structured bulk op map ({index, tool, name, type, source, target}). */
    private static Map<String, Object> op(int index, String tool, String name, String type,
            String source, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("tool", tool);
        if (name != null) m.put("name", name);
        if (type != null) m.put("type", type);
        if (source != null) m.put("source", source);
        if (target != null) m.put("target", target);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> bulkStructured(int operationCount, Map<String, Object>... ops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationCount", operationCount);
        m.put("operations", List.of(ops));
        return m;
    }

    private static String rollupText(ApprovalCardModel c) {
        return String.join(" · ", c.rollup().stream().map(RollupToken::text).toList());
    }

    // ---- effect rollup ----

    @Test
    public void shouldDeriveAdditiveRollup_forSingleCreateElement() {
        ApprovalCardModel c = card("p-1", "create-element", "Create Foo",
                Map.of("type", "ApplicationComponent", "name", "Foo"), "2026-01-01T00:00:00Z");

        assertEquals("1 new", rollupText(c));
        assertFalse(c.hasDestructive());
        assertFalse(c.rollup().get(0).destructive());
    }

    @Test
    public void shouldClassifyDelete_asDestructiveAmberToken() {
        ApprovalCardModel c = card("p-2", "delete-element",
                "Delete ApplicationComponent: Legacy Batch Job (cascade: 0 relationships, 0 view references)",
                Map.of("elementId", "id-123", "relationshipsRemoved", 0), "2026-01-01T00:00:00Z");

        assertEquals("1 delete", rollupText(c));
        assertTrue(c.hasDestructive());
        assertTrue(c.rollup().get(0).destructive());
    }

    @Test
    public void shouldClassifyRemoveFromView_asDestructive() {
        ApprovalCardModel c = card("p-3", "remove-from-view", "Remove Foo from view",
                Map.of("viewObjectId", "vo-1"), "2026-01-01T00:00:00Z");

        assertTrue(c.hasDestructive());
        assertEquals("1 removal", rollupText(c));
    }

    @Test
    public void shouldDeriveBulkRollup_fromOperations_withAmberDeleteLast() {
        Map<String, Object> changes = bulkOps(10,
                "0: create-element", "1: create-element", "2: create-element",
                "3: create-element", "4: create-element", "5: create-element",
                "6: create-relationship", "7: create-relationship", "8: create-relationship",
                "9: delete-element");

        ApprovalCardModel c = card("p-7", "bulk-mutate", "Bulk mutation (10 operations)",
                changes, "2026-01-01T00:00:00Z");

        // Additive tokens first, destructive amber token last — matches the design example.
        assertEquals("6 new · 3 connections · 1 delete", rollupText(c));
        assertTrue(c.hasDestructive());
        List<RollupToken> r = c.rollup();
        assertFalse(r.get(0).destructive());
        assertFalse(r.get(1).destructive());
        assertTrue(r.get(2).destructive());
    }

    // ---- expanded rows: name resolution + hoist ----

    @Test
    public void shouldResolveStructuredName_forCreate() {
        ApprovalCardModel c = card("p-1", "create-element", "Create Foo",
                Map.of("type", "ApplicationComponent", "name", "Payment Gateway"),
                "2026-01-01T00:00:00Z");

        assertEquals(1, c.rows().size());
        ChangeRow row = c.rows().get(0);
        assertTrue(row.nameResolved());
        assertTrue(row.text().contains("Payment Gateway"));
        assertTrue(row.text().contains("ApplicationComponent"));
        assertEquals("▢", row.icon()); // model element = outlined box (was "+")
    }

    @Test
    public void shouldFallBackToDescription_forDelete_whenNoStructuredName() {
        // delete-element's proposedChanges carries only ids; the name lives in the description.
        ApprovalCardModel c = card("p-2", "delete-element",
                "Delete ApplicationComponent: Legacy Batch Job (cascade: 0 relationships, 0 view references)",
                Map.of("elementId", "id-123"), "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertTrue(row.nameResolved());
        assertTrue(row.text().contains("Legacy Batch Job"));
        assertTrue(row.destructive());
        assertEquals("🗑", row.icon());
    }

    @Test
    public void shouldFallBackToId_whenNoNameAndNoDescription() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("elementId", "id-xyz");
        ApprovalCardModel c = card("p-9", "delete-element", null, changes, "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertFalse("Raw id is the fallback only when no name is resolvable", row.nameResolved());
        assertTrue(row.text().contains("id-xyz"));
    }

    @Test
    public void shouldClassifyDestructive_inBulk_whenOperationsAreMapShaped() {
        // Defensive: if a future operations shape carries Map entries with a "tool" key, the
        // destructive interlock must still fire (not silently collapse to "N changes").
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("operationCount", 2);
        changes.put("operations", List.of(
                Map.of("index", 0, "tool", "create-element"),
                Map.of("index", 1, "tool", "delete-element")));

        ApprovalCardModel c = card("p-5", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        assertTrue(c.hasDestructive());
        assertEquals("1 new · 1 delete", rollupText(c));
    }

    @Test
    public void shouldOrderDestructiveTokens_removalThenClearThenDelete() {
        Map<String, Object> changes = bulkOps(4,
                "0: create-element", "1: remove-from-view", "2: clear-view", "3: delete-element");

        ApprovalCardModel c = card("p-6", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        // Additive first, then the destructive group in a stable, pinned order.
        assertEquals("1 new · 1 removal · 1 clear · 1 delete", rollupText(c));
    }

    @Test
    public void shouldHoistDeleteRows_toTop_inBulk() {
        Map<String, Object> changes = bulkOps(3,
                "0: create-element", "1: delete-element", "2: create-relationship");

        ApprovalCardModel c = card("p-8", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        assertEquals(3, c.rows().size());
        // The delete row is hoisted to the top even though it was the 2nd operation.
        assertTrue(c.rows().get(0).destructive());
        assertTrue(c.rows().get(0).text().toLowerCase().contains("delete"));
        assertFalse(c.rows().get(1).destructive());
        assertFalse(c.rows().get(2).destructive());
    }

    // ---- intent slot + raw details ----

    @Test
    public void shouldHaveNullIntent_whenProposalCarriesNone() {
        ApprovalCardModel c = card("p-1", "create-element", "Create Foo",
                Map.of("name", "Foo"), "2026-01-01T00:00:00Z");
        assertNull("absent intent leaves the slot null (zero-height in the view)", c.intentText());
    }

    @Test
    public void shouldIncludeRawDetailsJson_forAuditors() {
        ApprovalCardModel c = card("p-1", "create-element", "Create Foo",
                Map.of("name", "Foo", "type", "Node"), "2026-01-01T00:00:00Z");
        assertTrue(c.rawDetailsJson().contains("proposedChanges"));
        assertTrue(c.rawDetailsJson().contains("Foo"));
    }

    // ---- copy-json payload (per-card Copy JSON action) ----

    @Test
    public void shouldProduceValidJson_forCopyPayload() throws Exception {
        ApprovalCardModel c = card("p-1", "create-element", "Create Foo",
                Map.of("name", "Foo", "type", "Node"), "2026-01-01T00:00:00Z");
        // Parses cleanly => valid JSON.
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(c.copyJson());
        assertEquals("p-1", node.get("proposalId").asText());
        assertEquals("create-element", node.get("tool").asText());
        assertEquals("pending", node.get("status").asText());
        assertTrue("payload carries the structured changes",
                node.has("proposedChanges"));
        assertTrue("proposedChanges is a nested JSON object, not a stringified map",
                node.get("proposedChanges").isObject());
        assertEquals("Foo", node.get("proposedChanges").get("name").asText());
    }

    @Test
    public void shouldIncludeIntentAndEffectDescription_whenProposalCarriesThem() throws Exception {
        ApprovalCardModel c = cardFull("p-2", "create-relationship", "Create rel",
                Map.of("type", "Association"), "2026-01-01T00:00:00Z",
                "A is associated with B", "linking the two actors");
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(c.copyJson());
        assertEquals("A is associated with B", node.get("effectDescription").asText());
        assertEquals("linking the two actors", node.get("intent").asText());
    }

    @Test
    public void shouldOmitNullFields_fromCopyPayload() throws Exception {
        // The 8-arg fixture leaves effectDescription, intent and currentState null.
        ApprovalCardModel c = card("p-3", "create-element", "Create Bar",
                Map.of("name", "Bar"), "2026-01-01T00:00:00Z");
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(c.copyJson());
        assertFalse("null intent is omitted", node.has("intent"));
        assertFalse("null effectDescription is omitted", node.has("effectDescription"));
        assertFalse("null currentState is omitted", node.has("currentState"));
    }

    @Test
    public void shouldNotLeakSessionId_inCopyPayload() {
        // sessionId is a routing key on PendingProposalView, not part of the ProposalDto payload.
        ProposalDto dto = new ProposalDto("p-4", "create-element", "pending", "Create Baz",
                null, Map.of("name", "Baz"), "Valid", "2026-01-01T00:00:00Z");
        ApprovalCardModel c = ApprovalCardModel.fromProposal(
                new PendingProposalView("secret-session-123", dto));
        assertFalse("copy payload must not contain the session routing key",
                c.copyJson().contains("secret-session-123"));
        assertFalse(c.copyJson().contains("sessionId"));
    }

    // ---- empty / gate-off / cards selection ----

    @Test
    public void shouldReturnEmptyGated_whenNoPendingAndGateOn() {
        ViewModel vm = ApprovalCardModel.viewModel(List.of(), true);
        assertEquals(State.EMPTY_GATED, vm.state());
        assertTrue(vm.cards().isEmpty());
    }

    @Test
    public void shouldReturnGateOff_whenNoPendingAndGateOff() {
        ViewModel vm = ApprovalCardModel.viewModel(List.of(), false);
        assertEquals(State.GATE_OFF, vm.state());
    }

    @Test
    public void shouldReturnCards_whenPending_regardlessOfGate() {
        List<PendingProposalView> pending = List.of(
                view("default", "p-1", "create-element", "Create Foo", Map.of("name", "Foo"),
                        "2026-01-01T00:00:00Z"));
        // Even with the gate off, a non-empty queue must stay drainable → CARDS.
        ViewModel vm = ApprovalCardModel.viewModel(pending, false);
        assertEquals(State.CARDS, vm.state());
        assertEquals(1, vm.cards().size());
    }

    // ---- bulk-approve selection ----

    @Test
    public void shouldSelectSafeApproveOrder_additiveOnly_oldestFirst() {
        ApprovalCardModel additiveNew = card("p-3", "create-element", "C",
                Map.of("name", "C"), "2026-01-03T00:00:00Z");
        ApprovalCardModel additiveOld = card("p-1", "create-element", "A",
                Map.of("name", "A"), "2026-01-01T00:00:00Z");
        ApprovalCardModel destructive = card("p-2", "delete-element", "Delete X: foo",
                Map.of("elementId", "x"), "2026-01-02T00:00:00Z");

        List<ApprovalCardModel> safe = ApprovalCardModel.safeApproveOrder(
                List.of(additiveNew, destructive, additiveOld));

        assertEquals("Destructive card is excluded from the safe set", 2, safe.size());
        assertEquals("p-1", safe.get(0).proposalId()); // oldest first
        assertEquals("p-3", safe.get(1).proposalId());
        assertFalse(safe.stream().anyMatch(ApprovalCardModel::hasDestructive));
    }

    @Test
    public void shouldSelectAllApproveOrder_oldestFirst_includingDestructive() {
        ApprovalCardModel c2 = card("p-2", "delete-element", "Delete X: foo",
                Map.of("elementId", "x"), "2026-01-02T00:00:00Z");
        ApprovalCardModel c1 = card("p-1", "create-element", "A",
                Map.of("name", "A"), "2026-01-01T00:00:00Z");

        List<ApprovalCardModel> all = ApprovalCardModel.allApproveOrder(List.of(c2, c1));

        assertEquals(2, all.size());
        assertEquals("p-1", all.get(0).proposalId());
        assertEquals("p-2", all.get(1).proposalId());
    }

    @Test
    public void shouldCountDestructiveCards() {
        ApprovalCardModel additive = card("p-1", "create-element", "A",
                Map.of("name", "A"), "2026-01-01T00:00:00Z");
        ApprovalCardModel del = card("p-2", "delete-element", "Delete X: foo",
                Map.of("elementId", "x"), "2026-01-02T00:00:00Z");
        ApprovalCardModel bulkWithDelete = card("p-3", "bulk-mutate", "Bulk",
                bulkOps(2, "0: create-element", "1: delete-element"), "2026-01-03T00:00:00Z");

        assertEquals(2, ApprovalCardModel.destructiveCount(List.of(additive, del, bulkWithDelete)));
    }

    // ---- intent population + hollow suppression ----

    @Test
    public void shouldPopulateIntent_whenSubstantive() {
        ApprovalCardModel c = cardFull("p-1", "bulk-mutate", "Bulk",
                bulkOps(1, "0: create-element"), "2026-01-01T00:00:00Z",
                null, "Wire the fraud-check path into checkout");
        assertEquals("Wire the fraud-check path into checkout", c.intentText());
    }

    @Test
    public void shouldTrimIntent_whenSurroundedByWhitespace() {
        ApprovalCardModel c = cardFull("p-1", "bulk-mutate", "Bulk",
                bulkOps(1, "0: create-element"), "2026-01-01T00:00:00Z",
                null, "   Add a customer onboarding flow  ");
        assertEquals("Add a customer onboarding flow", c.intentText());
    }

    @Test
    public void shouldSuppressHollowIntent_genericPhrases() {
        // Every generic phrase + blank/whitespace + tool-restating note collapses to null.
        for (String hollow : new String[] {
                "", "   ", "Updating the model", "updating the model", "Making changes",
                "making change", "Editing", "edit", "Changes", "change", "modify",
                "various changes", "N/A", "none", "stuff" }) {
            assertTrue("expected hollow: '" + hollow + "'",
                    ApprovalCardModel.isHollowIntent(hollow, "bulk-mutate"));
        }
        assertTrue("null is hollow", ApprovalCardModel.isHollowIntent(null, "bulk-mutate"));
    }

    @Test
    public void shouldSuppressHollowIntent_thatRestatesTheTool() {
        assertTrue(ApprovalCardModel.isHollowIntent("bulk-mutate", "bulk-mutate"));
        assertTrue(ApprovalCardModel.isHollowIntent("bulk mutate", "bulk-mutate"));
        // Restating just the verb of a tool is hollow too.
        assertTrue(ApprovalCardModel.isHollowIntent("create", "create-element"));
        assertTrue(ApprovalCardModel.isHollowIntent("Delete.", "delete-relationship"));
    }

    @Test
    public void shouldSuppressHollowIntent_thatRestatesVisualConnectionVerb() {
        // The new compound verbs are still hollow when an intent merely restates them — a note
        // that just echoes "Add connection" / "Update connection" carries no decision-useful info
        // (intended; same rule as restating any tool verb). A substantive note is still preserved.
        assertTrue(ApprovalCardModel.isHollowIntent("Add connection", "add-connection-to-view"));
        assertTrue(ApprovalCardModel.isHollowIntent("Update connection.", "update-view-connection"));
        assertFalse(ApprovalCardModel.isHollowIntent(
                "Add connection to reflect the new fraud-check routing", "add-connection-to-view"));
    }

    @Test
    public void shouldPassSubstantiveIntent_throughSuppression() {
        assertFalse(ApprovalCardModel.isHollowIntent(
                "Wire the fraud-check path into checkout", "bulk-mutate"));
        assertFalse(ApprovalCardModel.isHollowIntent(
                "Create the payments bounded context", "create-element"));
    }

    @Test
    public void shouldSuppressHollowIntent_onTheCard() {
        ApprovalCardModel c = cardFull("p-1", "bulk-mutate", "Bulk",
                bulkOps(1, "0: create-element"), "2026-01-01T00:00:00Z",
                null, "Updating the model");
        assertNull("hollow note must not occupy the trust slot", c.intentText());
    }

    // ---- effect-preferred row derivation ----

    @Test
    public void shouldPreferEffectDescription_overMechanicalDescription_forRelationship() {
        // create-relationship: proposedChanges carry only ids; effectDescription names the endpoints.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("type", "ServingRelationship");
        changes.put("sourceId", "id-src");
        changes.put("targetId", "id-tgt");
        ApprovalCardModel c = cardFull("p-1", "create-relationship",
                "Create ServingRelationship: id-src → id-tgt", changes, "2026-01-01T00:00:00Z",
                "Create ServingRelationship: 'Payment Gateway' → 'Fraud Engine'", null);

        ChangeRow row = c.rows().get(0);
        assertTrue(row.nameResolved());
        assertEquals("Create ServingRelationship: 'Payment Gateway' → 'Fraud Engine'", row.text());
        assertEquals("↔", row.icon());
    }

    @Test
    public void shouldFallBackToDescription_whenEffectDescriptionAbsent() {
        // No effectDescription → the existing description→id honesty ladder is preserved.
        ApprovalCardModel c = cardFull("p-2", "delete-element",
                "Delete ApplicationComponent: Legacy Batch Job (cascade: 0 relationships, 0 view references)",
                Map.of("elementId", "id-123"), "2026-01-01T00:00:00Z", null, null);
        ChangeRow row = c.rows().get(0);
        assertTrue(row.text().contains("Legacy Batch Job"));
    }

    // ---- enriched bulk op parsing into named rows ----

    @Test
    public void shouldRenderNamedElementRow_fromStructuredBulkOp() {
        Map<String, Object> changes = bulkStructured(1,
                op(0, "create-element", "Payment Gateway", "ApplicationComponent", null, null));
        ApprovalCardModel c = card("p-1", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("Create \"Payment Gateway\" (ApplicationComponent)", row.text());
        assertTrue(row.nameResolved());
        assertEquals("▢", row.icon()); // model element = outlined box (was "+")
    }

    @Test
    public void shouldRenderNamedConnectionRow_fromStructuredBulkOp() {
        Map<String, Object> changes = bulkStructured(1,
                op(0, "create-relationship", null, "ServingRelationship",
                        "Payment Gateway", "Fraud Engine"));
        ApprovalCardModel c = card("p-1", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("Connect \"Payment Gateway\" → \"Fraud Engine\" (ServingRelationship)", row.text());
        assertTrue(row.nameResolved());
        assertEquals("↔", row.icon());
    }

    @Test
    public void shouldRenderNamedRows_forMixedBulk_closingTheS6bGateDefect() {
        // The exact finding: rows used to read "Create element" / "Connect relationship".
        Map<String, Object> changes = bulkStructured(2,
                op(0, "create-element", "Payment Gateway", "ApplicationComponent", null, null),
                op(1, "create-relationship", null, "ServingRelationship",
                        "Payment Gateway", "Fraud Engine"));
        ApprovalCardModel c = card("p-7", "bulk-mutate", "Bulk mutation (2 operations)",
                changes, "2026-01-01T00:00:00Z");

        List<String> texts = c.rows().stream().map(ChangeRow::text).toList();
        assertTrue(texts.contains("Create \"Payment Gateway\" (ApplicationComponent)"));
        assertTrue(texts.contains("Connect \"Payment Gateway\" → \"Fraud Engine\" (ServingRelationship)"));
        // No row degrades to the coarse "Create element" / "Connect relationship" wording.
        assertFalse(texts.contains("Create element"));
        assertFalse(texts.contains("Connect relationship"));
    }

    @Test
    public void shouldPreserveDestructiveClassification_withStructuredBulkOps() {
        Map<String, Object> changes = bulkStructured(2,
                op(0, "create-element", "Foo", "Node", null, null),
                op(1, "delete-element", "Bar", "Node", null, null));
        ApprovalCardModel c = card("p-5", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        assertTrue(c.hasDestructive());
        assertEquals("1 new · 1 delete", rollupText(c));
        // Delete row hoisted to the top, still amber.
        assertTrue(c.rows().get(0).destructive());
        assertTrue(c.rows().get(0).text().contains("Bar"));
    }

    @Test
    public void shouldFallBackToCoarseNoun_whenStructuredOpLacksName() {
        // An op with neither name nor endpoints degrades to today's coarse wording (never crashes).
        Map<String, Object> changes = bulkStructured(1,
                op(0, "update-element", null, null, null, null));
        ApprovalCardModel c = card("p-1", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("Update element", row.text());
        assertFalse(row.nameResolved());
    }

    @Test
    public void shouldNotRenderUnknownPlaceholder_whenRelationshipEndpointsUnresolved() {
        // The server omits an unresolved endpoint key rather than writing a
        // literal "(unknown)". A relationship op with no resolvable source/target must degrade to
        // the honest coarse noun — never surface "(unknown)" as if it were a real element name.
        Map<String, Object> changes = bulkStructured(1,
                op(0, "create-relationship", null, "ServingRelationship", null, null));
        ApprovalCardModel c = card("p-1", "bulk-mutate", "Bulk", changes, "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("Connect relationship", row.text());
        assertFalse(row.nameResolved());
        assertFalse(row.text().contains("(unknown)"));
    }

    // ---- visual-connection kind — icon / category / verb ----

    @Test
    public void shouldRenderVisualConnectionCard_withConnectionIconCategoryAndVerb() {
        // add-connection-to-view: a drawn line. Its own kind — distinct from a placed node (▣) and
        // from a model relationship (↔). With no server effectDescription the row falls to the verb
        // + id ladder, exercising verbOf/iconOf/categoryOf for the visual-connection kind.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("relationshipId", "rel-9");
        ApprovalCardModel c = card("p-1", "add-connection-to-view", null, changes,
                "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("⇿", row.icon());
        assertEquals("Add connection rel-9", row.text());
        assertEquals("1 view connection", rollupText(c)); // VIEW_CONNECTION rollup noun
        assertFalse(c.hasDestructive());
    }

    @Test
    public void shouldRenderNamedVisualConnectionCard_fromServerEffectDescription() {
        // The shipping path: the server resolves the endpoints into effectDescription; the
        // card renders it verbatim under the visual-connection icon.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("relationshipId", "rel-9");
        ApprovalCardModel c = cardFull("p-1", "add-connection-to-view",
                "Add connection for relationship rel-9 to view", changes, "2026-01-01T00:00:00Z",
                "Add connection ServingRelationship: 'Mobile Banking App' → 'Open Banking API Gateway'",
                null);

        ChangeRow row = c.rows().get(0);
        assertEquals("⇿", row.icon());
        assertEquals(
                "Add connection ServingRelationship: 'Mobile Banking App' → 'Open Banking API Gateway'",
                row.text());
        assertTrue(row.nameResolved());
    }

    @Test
    public void shouldRenderUpdateViewConnection_withEditIconAndConnectionVerb() {
        // update-view-connection stays UPDATE (action dominates kind → keeps ✎) but the verb is
        // connection-flavoured ("Update connection", never bare "Update").
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("relationshipId", "rel-3");
        ApprovalCardModel c = card("p-1", "update-view-connection", null, changes,
                "2026-01-01T00:00:00Z");

        ChangeRow row = c.rows().get(0);
        assertEquals("✎", row.icon());
        assertEquals("Update connection rel-3", row.text());
        assertEquals("1 update", rollupText(c)); // category stays UPDATE
    }

    @Test
    public void shouldRenderVisualObject_withFilledBoxIcon() {
        // add-to-view: a placed node (visual object) = filled box ▣ — distinct from the outlined-box
        // model element ▢ and from the ⇿ drawn connection.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("elementId", "id-7");
        ApprovalCardModel c = card("p-1", "add-to-view", null, changes, "2026-01-01T00:00:00Z");

        assertEquals("▣", c.rows().get(0).icon());
        assertEquals("1 view edit", rollupText(c));
    }

    @Test
    public void shouldDistinguishAllFourConceptKinds_byIcon() {
        // The whole point: element / relationship / visual-object / visual-connection no longer
        // collapse onto one glyph. All four icons must differ.
        String element = card("p-1", "create-element", null,
                Map.of("name", "Foo", "type", "Node"), "2026-01-01T00:00:00Z").rows().get(0).icon();
        String relationship = card("p-2", "create-relationship", null,
                Map.of("type", "ServingRelationship"), "2026-01-01T00:00:00Z").rows().get(0).icon();
        String visualObject = card("p-3", "add-to-view", null,
                Map.of("elementId", "id-1"), "2026-01-01T00:00:00Z").rows().get(0).icon();
        String visualConnection = card("p-4", "add-connection-to-view", null,
                Map.of("relationshipId", "rel-1"), "2026-01-01T00:00:00Z").rows().get(0).icon();

        assertEquals("▢", element);
        assertEquals("↔", relationship);
        assertEquals("▣", visualObject);
        assertEquals("⇿", visualConnection);
        // Pairwise distinct — a placed node ≠ a drawn line; a model rel ≠ a visual connection.
        assertEquals(4, java.util.Set.of(element, relationship, visualObject, visualConnection).size());
    }

    // ---- kindLabelForIcon (icon-tooltip vocabulary) ----

    @Test
    public void shouldLabelModelElementIcon_whenOutlinedBox() {
        assertEquals("Model element", ApprovalCardModel.kindLabelForIcon("▢"));
    }

    @Test
    public void shouldLabelVisualObjectIcon_whenFilledBox() {
        assertEquals("Visual object (a placed node)", ApprovalCardModel.kindLabelForIcon("▣"));
    }

    @Test
    public void shouldLabelModelRelationshipIcon_whenDoubleArrow() {
        assertEquals("Model relationship", ApprovalCardModel.kindLabelForIcon("↔"));
    }

    @Test
    public void shouldLabelVisualConnectionIcon_whenDrawnLine() {
        assertEquals("Visual connection (a drawn line)", ApprovalCardModel.kindLabelForIcon("⇿"));
    }

    @Test
    public void shouldLabelUpdateIcon_whenPencil() {
        assertEquals("Update", ApprovalCardModel.kindLabelForIcon("✎"));
    }

    @Test
    public void shouldLabelDeletionIcon_whenTrash() {
        assertEquals("Deletion / removal", ApprovalCardModel.kindLabelForIcon("🗑"));
    }

    @Test
    public void shouldLabelGenericAdditiveIcon_whenPlus() {
        assertEquals("Change", ApprovalCardModel.kindLabelForIcon("+"));
    }

    @Test
    public void shouldFallBackToChange_whenIconUnknownOrBlank() {
        assertEquals("Change", ApprovalCardModel.kindLabelForIcon("?"));
        assertEquals("Change", ApprovalCardModel.kindLabelForIcon(""));
        assertEquals("Change", ApprovalCardModel.kindLabelForIcon(null));
    }

    @Test
    public void shouldLabelEveryIconThatIconOfCanProduce() {
        // Guard: kindLabelForIcon must name every glyph iconOf emits (no unlabeled icon can leak to a
        // tooltip). Exercise the kinds end-to-end through real cards rather than hard-coding the set.
        java.util.Map<String, String> probes = new LinkedHashMap<>();
        probes.put("create-element", "Model element");          // ▢
        probes.put("add-to-view", "Visual object (a placed node)"); // ▣
        probes.put("create-relationship", "Model relationship");    // ↔
        probes.put("add-connection-to-view", "Visual connection (a drawn line)"); // ⇿
        probes.put("update-element", "Update");                 // ✎
        probes.put("delete-element", "Deletion / removal");     // 🗑
        probes.put("create-view", "Change");                    // + (catch-all: view/folder/other additive)
        for (Map.Entry<String, String> e : probes.entrySet()) {
            String icon = card("p-1", e.getKey(), null, Map.of(), "2026-01-01T00:00:00Z")
                    .rows().get(0).icon();
            assertEquals("kind label for " + e.getKey() + " (" + icon + ")",
                    e.getValue(), ApprovalCardModel.kindLabelForIcon(icon));
        }
    }

    // ---- single-op predicate (always-show-the-row / drop-the-toggle decision) ----

    @Test
    public void shouldBeSingleOp_whenOneCreateRow() {
        ApprovalCardModel c = card("p-1", "create-element",
                "Create Foo", Map.of("type", "ApplicationComponent", "name", "Foo"),
                "2026-01-01T00:00:00Z");

        assertEquals(1, c.rows().size());
        assertTrue(c.isSingleOp());
    }

    @Test
    public void shouldBeSingleOp_whenOneDeleteRow() {
        ApprovalCardModel c = card("p-2", "delete-element",
                "Delete ApplicationComponent: Legacy Batch Job", Map.of("elementId", "id-123"),
                "2026-01-01T00:00:00Z");

        assertEquals(1, c.rows().size());
        assertTrue(c.isSingleOp());
    }

    @Test
    public void shouldBeSingleOp_whenOneUpdateRow() {
        ApprovalCardModel c = card("p-3", "update-element",
                "Update Foo", Map.of("elementId", "id-7", "name", "Foo v2"),
                "2026-01-01T00:00:00Z");

        assertEquals(1, c.rows().size());
        assertTrue(c.isSingleOp());
    }

    @Test
    public void shouldBeSingleOp_whenOneOperationBulkMutate() {
        // A 1-operation bulk-mutate is one change to show → single-op (no toggle).
        Map<String, Object> changes = bulkStructured(1,
                op(0, "create-element", "Payment Gateway", "ApplicationComponent", null, null));
        ApprovalCardModel c = card("p-4", "bulk-mutate", "Bulk mutation (1 operation)",
                changes, "2026-01-01T00:00:00Z");

        assertEquals(1, c.rows().size());
        assertTrue(c.isSingleOp());
    }

    @Test
    public void shouldNotBeSingleOp_whenMultiOperationBulkMutate() {
        // A multi-operation bulk-mutate keeps the collapse/expand rollup as the scan layer.
        Map<String, Object> changes = bulkStructured(2,
                op(0, "create-element", "Payment Gateway", "ApplicationComponent", null, null),
                op(1, "delete-element", "Legacy Job", "Node", null, null));
        ApprovalCardModel c = card("p-5", "bulk-mutate", "Bulk mutation (2 operations)",
                changes, "2026-01-01T00:00:00Z");

        assertEquals(2, c.rows().size());
        assertFalse(c.isSingleOp());
    }

    @Test
    public void shouldBeSingleOpAndDestructive_forDeleteElement() {
        // The auto-satisfy disposition relies on BOTH facts holding for a single-op destructive
        // card: isSingleOp() (→ no toggle, Approve auto-enabled) AND hasDestructive() (→ amber row).
        // Pin them together so a future deriveRows change that splits a delete into >1 row (which would
        // flip isSingleOp() to false and re-gate the interlock) is caught here.
        ApprovalCardModel c = card("p-d", "delete-element",
                "Delete ApplicationComponent: Foo", Map.of("elementId", "id-1"),
                "2026-01-01T00:00:00Z");

        assertTrue(c.isSingleOp());
        assertTrue(c.hasDestructive());
    }
}
