package net.vheerden.archi.mcp.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Headless presentation logic for one Pending Approvals card.
 *
 * <p>This is the <strong>thin-renderer split</strong>: every <em>decision</em> the dock view makes
 * — the effect-rollup tokens, which counts are destructive (amber), how a change row reads, whether
 * deletes are hoisted, which empty/gate-off panel shows, and the bulk-approve ordering — lives here
 * as pure Java with <strong>no SWT/EMF/GEF imports</strong>, so it is unit-tested headlessly. The
 * {@code PendingApprovalsView} {@code ViewPart} is a thin SWT renderer over this model.</p>
 *
 * <p><strong>What it renders from (the {@link ProposalDto}):</strong> the effect
 * rollup is derived client-side from {@code tool} + {@code proposedChanges} (for {@code bulk-mutate},
 * the {@code operations} list). Names are taken wherever the DTO provides one — the structured
 * {@code name} for creates, or the mechanical {@code description} sentence for deletes/removals
 * (whose {@code proposedChanges} carries only ids) — falling back to a raw id only when neither
 * exists. The row/headline prefers the server-owned {@code effectDescription} (which names
 * a relationship's endpoints) over the mechanical {@code description}, and bulk ops are parsed from a
 * structured shape into named rows. The {@code intentText} slot is populated from the agent's
 * {@code intent} after {@linkplain #isHollowIntent hollow-note suppression}.</p>
 *
 * @param sessionId      the session the proposal lives in (Approve/Reject routing key)
 * @param proposalId     the proposal id (e.g. {@code p-7})
 * @param tool           the MCP tool that produced the proposal
 * @param createdAt      ISO-8601 creation timestamp (display + oldest-first ordering)
 * @param rollup         collapsed-headline effect tokens (additive first, destructive last/amber)
 * @param hasDestructive whether any change is destructive (drives the expand-first interlock)
 * @param intentText     the agent's intent note (hollow notes suppressed), or {@code null} when absent
 * @param rows           expanded per-change rows with destructive rows hoisted to the top
 * @param rawDetailsJson pretty-printed raw params for the {@code Technical details} auditor disclosure
 * @param copyJson       full pretty-printed proposal payload for the per-card Copy JSON action
 */
public record ApprovalCardModel(
        String sessionId,
        String proposalId,
        String tool,
        String createdAt,
        List<RollupToken> rollup,
        boolean hasDestructive,
        String intentText,
        List<ChangeRow> rows,
        String rawDetailsJson,
        String copyJson) {

    // NON_NULL so the copied/raw payloads omit null fields without depending on per-DTO
    // annotations — matches the project-wide JSON convention (camelCase + null omission).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Oldest-first by creation instant; stable, so equal/unparseable timestamps keep encounter order. */
    private static final Comparator<ApprovalCardModel> BY_CREATED =
            Comparator.comparing(c -> parseInstant(c.createdAt()));

    /** The top-level state the dock view shows. */
    public enum State { CARDS, EMPTY_GATED, GATE_OFF }

    /** One token of the collapsed effect rollup (e.g. {@code "3 connections"}); destructive → amber. */
    public record RollupToken(String text, boolean destructive) {}

    /** One expanded change row: {@code icon} + {@code text}; {@code destructive} → amber + hoisted. */
    public record ChangeRow(String icon, String text, boolean destructive, boolean nameResolved) {}

    /** The whole-view model: which {@link State} to render and, when {@code CARDS}, the cards. */
    public record ViewModel(State state, List<ApprovalCardModel> cards) {}

    /**
     * Whether this card maps to exactly <strong>one</strong> change row. A single-op card always
     * shows its one row and drops the {@code Show changes / Hide changes} toggle — expanding to reveal a
     * lone row that ≈ the collapsed rollup earns nothing. A multi-op card ({@code rows().size() > 1})
     * keeps the collapse/expand rollup as the scan layer on busy queues.
     *
     * <p>Pure and SWT-free (it reads only the already-derived {@link #rows()}), so the renderer's
     * single-op decision is unit-tested without a display. A non-bulk tool always yields exactly one
     * row; a {@code bulk-mutate} yields one row per operation (one fallback row when its ops are
     * unparseable) — so a 1-operation {@code bulk-mutate} is single-op (one change to show) while a
     * multi-operation {@code bulk-mutate} is not.</p>
     */
    public boolean isSingleOp() {
        return rows().size() == 1;
    }

    // ---- Effect categories -------------------------------------------------

    /**
     * Effect categories in <strong>rollup display order</strong> (additive first, destructive last
     * so amber tokens trail). {@code noun} is the singular form; {@code destructive} drives the amber
     * rendering and the expand-first interlock.
     */
    private enum Category {
        NEW("new", false),
        CONNECTION("connection", false),
        UPDATE("update", false),
        VIEW("view", false),
        VIEW_EDIT("view edit", false),
        VIEW_CONNECTION("view connection", false),
        FOLDER("folder", false),
        OTHER("change", false),
        REMOVAL("removal", true),
        CLEAR("clear", true),
        DELETE("delete", true);

        final String noun;
        final boolean destructive;

        Category(String noun, boolean destructive) {
            this.noun = noun;
            this.destructive = destructive;
        }
    }

    private static Category categoryOf(String tool) {
        if (tool == null) {
            return Category.OTHER;
        }
        if (tool.startsWith("delete-")) {
            return Category.DELETE;
        }
        if (tool.equals("remove-from-view")) {
            return Category.REMOVAL;
        }
        if (tool.equals("clear-view")) {
            return Category.CLEAR;
        }
        if (tool.equals("create-relationship")) {
            return Category.CONNECTION;
        }
        if (tool.equals("create-view") || tool.equals("clone-view")) {
            return Category.VIEW;
        }
        if (tool.equals("create-folder")) {
            return Category.FOLDER;
        }
        if (tool.startsWith("create-") || tool.equals("get-or-create-element")) {
            return Category.NEW;
        }
        if (tool.equals("add-connection-to-view")) {
            return Category.VIEW_CONNECTION;
        }
        if (tool.startsWith("add-")) {
            return Category.VIEW_EDIT;
        }
        if (tool.startsWith("update-") || tool.equals("move-to-folder")) {
            return Category.UPDATE;
        }
        return Category.OTHER;
    }

    /** True if applying {@code tool} destroys or removes something (delete-*, remove-from-view, clear-view). */
    static boolean isDestructiveTool(String tool) {
        return categoryOf(tool).destructive;
    }

    private static String verbOf(String tool) {
        if (tool == null) {
            return "Change";
        }
        if (tool.startsWith("delete-")) {
            return "Delete";
        }
        if (tool.equals("remove-from-view")) {
            return "Remove";
        }
        if (tool.equals("clear-view")) {
            return "Clear view";
        }
        if (tool.equals("create-relationship")) {
            return "Connect";
        }
        if (tool.equals("clone-view")) {
            return "Clone";
        }
        if (tool.startsWith("create-") || tool.equals("get-or-create-element")) {
            return "Create";
        }
        if (tool.equals("add-connection-to-view")) {
            return "Add connection";
        }
        if (tool.startsWith("add-")) {
            return "Add";
        }
        if (tool.equals("update-view-connection")) {
            return "Update connection";
        }
        if (tool.startsWith("update-") || tool.equals("move-to-folder")) {
            return "Update";
        }
        return "Change";
    }

    /**
     * The per-kind card glyph. Icons encode the concept <em>kind</em> so a human can tell the
     * four apart at a glance: model element {@code ▢} (outlined box) vs visual object {@code ▣}
     * (filled box) — a placed node; model relationship {@code ↔} vs visual connection {@code ⇿} — a
     * drawn line. Action dominates kind: destructive is always {@code 🗑} and an update is always
     * {@code ✎}. Colour is <strong>not</strong> used to encode kind (amber stays destructive-only —
     * design ruling). This is the <strong>single swap-point</strong> for the glyph set: if a
     * platform tofu-renders the geometric glyphs, swap to the ASCII-robust fallback
     * here ({@code [ ]} / {@code [+]} / {@code ↔} / {@code →|}) — a one-method change, not a redesign.
     */
    private static String iconOf(String tool) {
        Category c = categoryOf(tool);
        if (c.destructive) {
            return "🗑"; // 🗑 destructive (delete/remove/clear) — action dominates kind
        }
        return switch (c) {
            case NEW -> "▢";             // model element (outlined box)
            case VIEW_EDIT -> "▣";       // visual object — a placed node (filled box)
            case CONNECTION -> "↔";      // model relationship
            case VIEW_CONNECTION -> "⇿"; // visual connection — a drawn line
            case UPDATE -> "✎";          // edit (action dominates kind)
            default -> "+";              // view/folder/other additive
        };
    }

    /**
     * The human-readable name for a kind glyph — the words a change-row's icon tooltip
     * shows so a first-time reviewer learns the {@link #iconOf} vocabulary on demand, with no visible
     * legend and no second colour channel (a progressive-disclosure complement to the glyph).
     * Pure (no SWT), so it is unit-tested headlessly and the thin-renderer split holds. The inverse of
     * {@link #iconOf}'s glyph set; any unknown/blank glyph degrades to the neutral {@code "Change"}.
     *
     * <p>Package-private (not {@code private} like {@link #iconOf}) on purpose: it is a thin-renderer
     * seam called by {@code PendingApprovalsView} and asserted by {@code ApprovalCardModelTest} — both
     * in this package — exactly like the other test-accessible derivations ({@code deriveIntent},
     * {@code isHollowIntent}, {@code isDestructiveTool}).</p>
     */
    static String kindLabelForIcon(String icon) {
        if (icon == null) {
            return "Change";
        }
        return switch (icon) {
            case "▢" -> "Model element";
            case "▣" -> "Visual object (a placed node)";
            case "↔" -> "Model relationship";
            case "⇿" -> "Visual connection (a drawn line)";
            case "✎" -> "Update";
            case "🗑" -> "Deletion / removal";
            default -> "Change"; // "+" additive and any unknown/blank glyph
        };
    }

    /** A coarse noun for a bulk sub-operation row, where no per-element name is available today. */
    private static String opNoun(String tool) {
        if (tool == null) {
            return "change";
        }
        if (tool.contains("relationship")) {
            return "relationship";
        }
        if (tool.contains("connection")) {
            return "connection";
        }
        if (tool.contains("specialization")) {
            return "specialization";
        }
        if (tool.contains("note")) {
            return "note";
        }
        if (tool.contains("group")) {
            return "group";
        }
        if (tool.contains("folder")) {
            return "folder";
        }
        if (tool.contains("element")) {
            return "element";
        }
        if (tool.contains("view")) {
            return "view object";
        }
        return "change";
    }

    // ---- Agent-intent suppression -------------------------------------

    /**
     * Low-information intent phrases that must never occupy the trust slot (design §4 D6 guardrail).
     * Conservative on purpose — only clearly-empty notes — so a real-but-terse intent is never eaten.
     */
    private static final Set<String> HOLLOW_INTENTS = Set.of(
            "updating the model", "update the model", "update model", "updating model",
            "making changes", "make changes", "making change", "make a change",
            "changes", "change", "editing", "edit", "editing the model",
            "modify", "modifying", "modifying the model", "updating", "creating",
            "doing stuff", "various changes", "misc", "stuff", "n/a", "none");

    /**
     * Resolves the displayable agent note: the trimmed intent, or {@code null} when the intent is
     * {@linkplain #isHollowIntent hollow} (so the card shows the effect as the headline instead).
     */
    static String deriveIntent(String intent, String tool) {
        if (intent == null || isHollowIntent(intent, tool)) {
            return null;
        }
        return intent.trim();
    }

    /**
     * True when {@code intent} carries no decision-useful information — empty/whitespace, a generic
     * phrase (see {@link #HOLLOW_INTENTS}), or text that merely restates the tool name or its verb.
     * Pure (no SWT), so it is unit-tested headlessly across every case.
     */
    static boolean isHollowIntent(String intent, String tool) {
        if (intent == null) {
            return true;
        }
        String norm = intent.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[.!?\\s]+$", "").trim();
        if (norm.isEmpty()) {
            return true;
        }
        if (HOLLOW_INTENTS.contains(norm)) {
            return true;
        }
        if (tool != null) {
            String t = tool.toLowerCase(Locale.ROOT);
            if (norm.equals(t) || norm.equals(t.replace('-', ' '))) {
                return true;
            }
            if (norm.equals(verbOf(tool).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ---- Factory -----------------------------------------------------------

    /**
     * Builds the card model for one pending proposal.
     *
     * @param view the proposal paired with its routing session id
     * @return the renderable card model
     */
    public static ApprovalCardModel fromProposal(PendingProposalView view) {
        ProposalDto dto = view.proposal();
        Map<Category, Integer> counts = deriveCounts(dto.tool(), dto.proposedChanges());
        List<RollupToken> rollup = buildRollup(counts);
        boolean hasDestructive = counts.keySet().stream().anyMatch(c -> c.destructive);
        List<ChangeRow> rows = deriveRows(dto.tool(), dto.effectDescription(), dto.description(),
                dto.proposedChanges(), dto.currentState());
        String raw = buildRawJson(dto);
        String copy = buildCopyJson(dto);
        // Populate the agent's-note slot, suppressing hollow notes so vagueness never occupies
        // the trust slot (the view keeps the slot zero-height when this is null).
        String intentText = deriveIntent(dto.intent(), dto.tool());
        return new ApprovalCardModel(view.sessionId(), dto.proposalId(), dto.tool(),
                dto.createdAt(), rollup, hasDestructive, intentText, rows, raw, copy);
    }

    /**
     * Selects the whole-view state from the live pending list and the gate bit.
     *
     * <p>If any proposals are pending the view always shows them (the queue must stay drainable even
     * if the gate was just toggled off); only an <em>empty</em> queue branches on the gate.</p>
     *
     * @param pending the aggregated pending proposals (oldest first)
     * @param gateOn  whether approval mode is currently ON
     * @return the view model to render
     */
    public static ViewModel viewModel(List<PendingProposalView> pending, boolean gateOn) {
        if (pending != null && !pending.isEmpty()) {
            List<ApprovalCardModel> cards = new ArrayList<>(pending.size());
            for (PendingProposalView v : pending) {
                cards.add(fromProposal(v));
            }
            return new ViewModel(State.CARDS, List.copyOf(cards));
        }
        return new ViewModel(gateOn ? State.EMPTY_GATED : State.GATE_OFF, List.of());
    }

    // ---- Bulk-approve selection ------------------------------------

    /**
     * The purely-additive cards (no destructive op) in oldest-first order — the set
     * {@code Approve all safe} applies without confirmation. The per-card destructive interlock is
     * never bypassed: destructive cards are simply excluded.
     */
    public static List<ApprovalCardModel> safeApproveOrder(List<ApprovalCardModel> cards) {
        return cards.stream().filter(c -> !c.hasDestructive()).sorted(BY_CREATED).toList();
    }

    /** All cards in oldest-first order — the set {@code Approve all} applies after one confirm. */
    public static List<ApprovalCardModel> allApproveOrder(List<ApprovalCardModel> cards) {
        return cards.stream().sorted(BY_CREATED).toList();
    }

    /** How many cards contain a destructive op — names the {@code Approve all} confirm-dialog count. */
    public static int destructiveCount(List<ApprovalCardModel> cards) {
        return (int) cards.stream().filter(ApprovalCardModel::hasDestructive).count();
    }

    // ---- Derivation internals ---------------------------------------------

    private static Map<Category, Integer> deriveCounts(String tool, Map<String, Object> proposedChanges) {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        if ("bulk-mutate".equals(tool)) {
            List<BulkOp> ops = parseBulkOps(proposedChanges);
            if (ops.isEmpty()) {
                int n = intValue(proposedChanges, "operationCount");
                if (n > 0) {
                    counts.merge(Category.OTHER, n, Integer::sum);
                }
            } else {
                for (BulkOp op : ops) {
                    counts.merge(categoryOf(op.tool()), 1, Integer::sum);
                }
            }
        } else {
            counts.merge(categoryOf(tool), 1, Integer::sum);
        }
        return counts;
    }

    private static List<RollupToken> buildRollup(Map<Category, Integer> counts) {
        List<RollupToken> rollup = new ArrayList<>();
        for (Category c : Category.values()) {
            Integer n = counts.get(c);
            if (n != null && n > 0) {
                rollup.add(new RollupToken(n + " " + pluralize(c.noun, n), c.destructive));
            }
        }
        return List.copyOf(rollup);
    }

    private static List<ChangeRow> deriveRows(String tool, String effectDescription,
            String description, Map<String, Object> proposedChanges, Map<String, Object> currentState) {
        List<ChangeRow> rows = new ArrayList<>();
        if ("bulk-mutate".equals(tool)) {
            List<BulkOp> ops = parseBulkOps(proposedChanges);
            for (BulkOp op : ops) {
                rows.add(bulkRow(op));
            }
            if (ops.isEmpty()) {
                String text = description != null && !description.isBlank() ? description : "Bulk mutation";
                rows.add(new ChangeRow(iconOf(tool), text, false, description != null && !description.isBlank()));
            }
        } else {
            rows.add(singleRow(tool, effectDescription, description, proposedChanges, currentState));
        }
        // Postcondition: every branch yields ≥1 row — a non-bulk tool always appends one singleRow,
        // and a bulk-mutate with no parseable ops appends exactly one fallback row. So rows is never
        // empty, which is what isSingleOp() (== rows().size() == 1) and the renderer's single-op/
        // multi-op branch depend on (a 0-row card would silently route to the multi-op/no-content
        // branch). A new tool added here must preserve the ≥1-row guarantee.
        return hoist(rows);
    }

    /**
     * Renders one bulk sub-operation row. A relationship op with resolved endpoints reads
     * {@code <verb> "<source>" → "<target>" (<Type>)}; a named element/entity op reads
     * {@code <verb> "<name>" (<type>)}; an op lacking a name falls back to today's coarse
     * {@code <verb> <noun>} so the destructive/amber classification is never lost.
     */
    private static ChangeRow bulkRow(BulkOp op) {
        String tool = op.tool();
        String icon = iconOf(tool);
        boolean destructive = categoryOf(tool).destructive;
        if (op.source() != null && op.target() != null) {
            String type = op.type() != null ? op.type() : opNoun(tool);
            return new ChangeRow(icon,
                    verbOf(tool) + " \"" + op.source() + "\" → \"" + op.target() + "\" (" + type + ")",
                    destructive, true);
        }
        if (op.name() != null) {
            String text = verbOf(tool) + " \"" + op.name() + "\""
                    + (op.type() != null ? " (" + op.type() + ")" : "");
            return new ChangeRow(icon, text, destructive, true);
        }
        return new ChangeRow(icon, verbOf(tool) + " " + opNoun(tool), destructive, false);
    }

    private static ChangeRow singleRow(String tool, String effectDescription, String description,
            Map<String, Object> proposedChanges, Map<String, Object> currentState) {
        boolean destructive = categoryOf(tool).destructive;
        String icon = iconOf(tool);
        String verb = verbOf(tool);

        // Prefer the server-owned rich effect text when present (names a relationship's
        // endpoints), falling back to the structured name, then the mechanical description, then id.
        if (effectDescription != null && !effectDescription.isBlank()) {
            return new ChangeRow(icon, effectDescription.trim(), destructive, true);
        }

        String structuredName = firstNonBlank(proposedChanges, "name", "newName", "label");
        if (structuredName != null) {
            String type = firstNonBlank(proposedChanges, "type", "conceptType");
            String text = verb + " \"" + structuredName + "\"" + (type != null ? " (" + type + ")" : "");
            return new ChangeRow(icon, text, destructive, true);
        }
        // Deletes/removals carry the name in the mechanical description, not in proposedChanges.
        if (description != null && !description.isBlank()) {
            return new ChangeRow(icon, description.trim(), destructive, true);
        }
        // Last resort: a raw id (id only when no name is resolvable anywhere).
        String id = firstNonBlank(proposedChanges, "elementId", "relationshipId", "viewId",
                "viewObjectId", "folderId", "conceptId", "id", "sourceId", "targetId");
        if (id == null) {
            id = firstNonBlank(currentState, "id", "elementId");
        }
        return new ChangeRow(icon, verb + " " + (id != null ? id : "(unknown)"), destructive, false);
    }

    /** Stable-sorts so destructive rows lead, preserving original order within each group. */
    private static List<ChangeRow> hoist(List<ChangeRow> rows) {
        List<ChangeRow> sorted = new ArrayList<>(rows.size());
        for (ChangeRow r : rows) {
            if (r.destructive()) {
                sorted.add(r);
            }
        }
        for (ChangeRow r : rows) {
            if (!r.destructive()) {
                sorted.add(r);
            }
        }
        return List.copyOf(sorted);
    }

    private static String buildRawJson(ProposalDto dto) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tool", dto.tool());
        if (dto.description() != null) {
            raw.put("description", dto.description());
        }
        if (dto.proposedChanges() != null) {
            raw.put("proposedChanges", dto.proposedChanges());
        }
        if (dto.currentState() != null) {
            raw.put("currentState", dto.currentState());
        }
        if (dto.validationSummary() != null) {
            raw.put("validationSummary", dto.validationSummary());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(raw);
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }

    /**
     * Builds the full proposal payload for the per-card Copy JSON action. Serializes the whole
     * {@link ProposalDto} (which is {@code @JsonInclude(NON_NULL)}), so the copied text is the
     * wire-accurate structured change — proposalId, tool, status, description, currentState,
     * proposedChanges, validationSummary, createdAt, and the nullable effectDescription / intent
     * when present — with null fields omitted. The session routing key is not part of the DTO and
     * is intentionally excluded.
     */
    private static String buildCopyJson(ProposalDto dto) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
        } catch (Exception e) {
            // Keep the fallback valid JSON — this payload is meant to be pasted into a JSON
            // parser / log, so a record toString() dump would be worse than a structured error.
            return "{\"error\": \"Could not serialize proposal to JSON\"}";
        }
    }

    // ---- small helpers -----------------------------------------------------

    /** One parsed bulk sub-operation for row + count derivation. All fields but {@code tool} nullable. */
    private record BulkOp(String tool, String name, String type, String source, String target) {}

    /**
     * Parses {@code proposedChanges.operations} into structured ops. Each entry is the rich
     * {@code {index, tool, name, type, source, target}} map the server now emits; a legacy
     * {@code "idx: tool"} string is still accepted (back-compat), yielding tool-only ops so the
     * destructive/amber classification keeps working.
     */
    private static List<BulkOp> parseBulkOps(Map<String, Object> proposedChanges) {
        if (proposedChanges == null) {
            return List.of();
        }
        Object ops = proposedChanges.get("operations");
        if (!(ops instanceof List<?> list)) {
            return List.of();
        }
        List<BulkOp> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> map) {
                out.add(new BulkOp(str(map.get("tool")), str(map.get("name")),
                        str(map.get("type")), str(map.get("source")), str(map.get("target"))));
            } else {
                out.add(new BulkOp(opTool(String.valueOf(o)), null, null, null, null));
            }
        }
        return out;
    }

    /** Extracts the tool from a legacy bulk op summary of the form {@code "0: create-element"}. */
    private static String opTool(String opSummary) {
        if (opSummary == null) {
            return "";
        }
        int colon = opSummary.indexOf(':');
        return (colon >= 0 ? opSummary.substring(colon + 1) : opSummary).trim();
    }

    /** String value of a map entry, or {@code null} when absent/blank. */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static int intValue(Map<String, Object> map, String key) {
        if (map == null) {
            return 0;
        }
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v != null ? Integer.parseInt(String.valueOf(v)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null) {
                String s = String.valueOf(v);
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String pluralize(String noun, int count) {
        if ("new".equals(noun)) {
            return "new";
        }
        return count == 1 ? noun : noun + "s";
    }

    private static Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
