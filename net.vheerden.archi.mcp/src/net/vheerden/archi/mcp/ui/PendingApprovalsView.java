package net.vheerden.archi.mcp.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.ApprovalQueueListener;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.server.ApprovalMode;
import net.vheerden.archi.mcp.server.ApprovalService;
import net.vheerden.archi.mcp.server.McpServerManager;
import net.vheerden.archi.mcp.server.McpServerStateListener;
import net.vheerden.archi.mcp.server.ServerState;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.ChangeRow;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.RollupToken;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.State;
import net.vheerden.archi.mcp.ui.ApprovalCardModel.ViewModel;

/**
 * The human-facing <strong>Pending Approvals</strong> dock view — the control surface
 * that makes the approval gate operable. The control-plane split removed {@code decide-mutation} from the agent and
 * re-pointed approve/reject to a non-MCP {@link ApprovalService} seam; this view wires real human
 * buttons to that seam so the queue drains (without it the queue deadlocks).
 *
 * <p><strong>Thin renderer.</strong> Every decision — effect rollup, destructive/amber
 * classification, name resolution, delete hoist, empty/gate-off panel choice, bulk-approve ordering
 * — lives in the headless {@link ApprovalCardModel}. This class only paints SWT widgets over that
 * model and calls the {@link ApprovalService} seam. It imports no EMF/GEF and never touches
 * {@code PendingProposal}/{@code Command} — only {@code ProposalDto}/{@code PendingProposalView}.</p>
 *
 * <p><strong>Live refresh.</strong> The view registers as an {@link ApprovalQueueListener};
 * the dispatcher fires it on the mutating (Jetty) thread and the view marshals to the SWT thread via
 * {@code Display.asyncExec}. A {@code Refresh} toolbar action is the belt-and-braces fallback.</p>
 */
public class PendingApprovalsView extends ViewPart
        implements ApprovalQueueListener, McpServerStateListener {

    /** Stable view id — referenced by {@code plugin.xml} and {@link OpenPendingApprovalsHandler}. */
    public static final String VIEW_ID = "net.vheerden.archi.mcp.view.pendingApprovals";

    private static final Logger logger = LoggerFactory.getLogger(PendingApprovalsView.class);

    private Composite root;
    private StackLayout stack;
    private ScrolledComposite scroll;
    private Composite cardContainer;
    private Composite emptyPanel;
    private Composite gateOffPanel;

    /** Accessible amber for destructive counts/rows; a managed SWT resource — disposed in {@link #dispose}. */
    private Color amber;

    /**
     * Uniform kind-icon accent — a single accent for <em>all</em> non-destructive kinds
     * (never a per-kind hue: kind is carried by glyph shape + wording). It must
     * <strong>yield to amber</strong> on destructive rows (amber stays the only "this deletes
     * something" colour). Sourced from the theme-aware system link foreground — a blue-family colour
     * that tracks light/dark theme and is the canonical colour-blind-safe complement to amber — so it
     * is a <strong>shared system colour and must NOT be disposed</strong>. This field is the
     * <strong>single swap-point</strong> for the accent (mirrors {@code iconOf}'s glyph swap-point):
     * the accent can be retuned by changing this one assignment; if a managed
     * {@code new Color(...)} is ever chosen instead, store it here and dispose it in {@link #dispose}.
     */
    private Color accent;

    /** Quieter italic register for the agent's-note line; a managed SWT resource — disposed in {@link #dispose}. */
    private Font intentFont;

    /** In-view action bar (real buttons on the canvas, not the part toolbar) for the bulk actions. */
    private Composite headerBar;
    private Button approveSafeButton;
    private Button approveAllButton;

    /** The service we are currently registered against (re-acquired across server restarts). */
    private ApprovalService registeredService;

    /**
     * Stable listener registered on {@link ApprovalMode} so the view swaps its empty-gated ↔
     * gate-off panel the instant the human toggles the mode (a mode flip is neither a queue nor a
     * server-state change). Held as a field so {@code add}/{@code remove} use the same instance.
     */
    private final Runnable approvalModeListener = this::asyncRebuild;

    /**
     * Proposal ids whose destructive interlock has been satisfied (rows expanded once). Tracked on
     * the view — not on the per-card widget — so the interlock survives a {@link #rebuild()} when a
     * <em>different</em> proposal arrives (otherwise a busy queue would re-disable Approve every time
     * a new card lands, trapping the human in an expand→rebuild loop).
     */
    private final java.util.Set<String> expandedProposalIds = new java.util.HashSet<>();

    private List<ApprovalCardModel> currentCards = List.of();

    @Override
    public void createPartControl(Composite parent) {
        amber = new Color(parent.getDisplay(), 0xB8, 0x6E, 0x00);

        // The single uniform kind-icon accent. Theme-aware blue-family system colour →
        // colour-blind-safe vs amber + tracks light/dark theme + shared (not disposed). Single
        // swap-point: change this one line to retune the accent.
        accent = parent.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND);

        // A quieter italic register for the agent's-note line (lighter than the effect rollup).
        FontData[] intentFontData = parent.getFont().getFontData();
        for (FontData fd : intentFontData) {
            fd.setStyle(fd.getStyle() | SWT.ITALIC);
        }
        intentFont = new Font(parent.getDisplay(), intentFontData);

        // container = [ in-view action bar ] over [ stacked body: cards | empty | gate-off ].
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout containerLayout = new GridLayout(1, false);
        containerLayout.marginWidth = 0;
        containerLayout.marginHeight = 0;
        containerLayout.verticalSpacing = 0;
        container.setLayout(containerLayout);

        buildHeaderBar(container);

        root = new Composite(container, SWT.NONE);
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        stack = new StackLayout();
        root.setLayout(stack);

        buildCardArea(root);
        emptyPanel = buildMessagePanel(root, "✓  No changes waiting",
                "The agent's changes will appear here for your approval.\n(Approval mode is ON — you're gated.)");
        gateOffPanel = buildMessagePanel(root, "Approval mode is OFF",
                "The agent's changes apply directly, without review.\n"
                        + "Turn it on from  MCP Server ▸ Approval Mode  to review changes here.");

        // Re-acquire the queue listener whenever the server (and its ApprovalService) starts/stops,
        // so the first proposal after a server start is never missed (the view may have opened while
        // the server was stopped, with no service to register against).
        McpServerManager.getInstance().addStateListener(this);
        // Repaint the instant the human toggles approval mode (swaps the empty ↔ gate-off panel).
        ApprovalMode.getInstance().addChangeListener(approvalModeListener);
        rebuild();
    }

    /** Builds the on-canvas action bar: the bulk-approve buttons + Refresh, right-aligned. */
    private void buildHeaderBar(Composite parent) {
        headerBar = new Composite(parent, SWT.NONE);
        headerBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(4, false);
        layout.marginWidth = 6;
        layout.marginHeight = 4;
        headerBar.setLayout(layout);

        // Leading spacer grabs the slack so the buttons sit on the right.
        Label spacer = new Label(headerBar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        approveSafeButton = new Button(headerBar, SWT.PUSH);
        approveSafeButton.setText("Approve all safe (0)");
        approveSafeButton.setToolTipText("Approve every purely-additive change, oldest first (no deletes).");
        approveSafeButton.addListener(SWT.Selection,
                e -> bulkApprove(ApprovalCardModel.safeApproveOrder(currentCards), false));

        approveAllButton = new Button(headerBar, SWT.PUSH);
        approveAllButton.setText("Approve all (0) ⚠");
        approveAllButton.setToolTipText("Approve every pending change, oldest first (one confirmation).");
        approveAllButton.addListener(SWT.Selection, e -> confirmAndApproveAll());

        Button refreshButton = new Button(headerBar, SWT.PUSH);
        refreshButton.setText("⟳ Refresh");
        refreshButton.setToolTipText("Re-read the pending queue.");
        refreshButton.addListener(SWT.Selection, e -> rebuild());
    }

    private void confirmAndApproveAll() {
        List<ApprovalCardModel> all = ApprovalCardModel.allApproveOrder(currentCards);
        int destructive = ApprovalCardModel.destructiveCount(currentCards);
        String message = destructive > 0
                ? "Approve all " + all.size() + " pending changes? Includes " + destructive
                        + (destructive == 1 ? " deletion." : " deletions.")
                : "Approve all " + all.size() + " pending changes?";
        if (MessageDialog.openConfirm(getSite().getShell(), "Approve all pending changes?", message)) {
            bulkApprove(all, true);
        }
    }

    private void buildCardArea(Composite parent) {
        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        cardContainer = new Composite(scroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.verticalSpacing = 8;
        cardContainer.setLayout(layout);
        scroll.setContent(cardContainer);

        // Paint the surround with the platform list background (theme-aware, NOT a
        // hardcoded white — reads correctly in dark theme) so the SWT.BORDER cards stand out as
        // tiles. A shared system colour — not disposed.
        Color listBg = parent.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        scroll.setBackground(listBg);
        cardContainer.setBackground(listBg);
    }

    private Composite buildMessagePanel(Composite parent, String heading, String body) {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 24;
        layout.marginHeight = 24;
        panel.setLayout(layout);
        // Match the card-area surround so all three StackLayout states share one
        // consistent (theme-aware) background. Shared system colour — not disposed.
        Color listBg = parent.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        panel.setBackground(listBg);
        Label headingLabel = new Label(panel, SWT.WRAP);
        headingLabel.setText(heading);
        headingLabel.setBackground(listBg);
        headingLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Label bodyLabel = new Label(panel, SWT.WRAP | SWT.CENTER);
        bodyLabel.setText(body);
        bodyLabel.setBackground(listBg);
        bodyLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
        return panel;
    }

    // ---- ApprovalQueueListener / McpServerStateListener (both fired off the SWT thread) ----

    @Override
    public void onQueueChanged() {
        asyncRebuild();
    }

    @Override
    public void onStateChanged(ServerState oldState, ServerState newState) {
        // The ApprovalService is created/dropped with the server — rebuild re-hooks the queue
        // listener against the new service and repaints (no missed first proposal).
        asyncRebuild();
    }

    private void asyncRebuild() {
        if (root == null || root.isDisposed()) {
            return;
        }
        Display display = root.getDisplay();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                if (root != null && !root.isDisposed()) {
                    rebuild();
                }
            });
        }
    }

    // ---- Rebuild ----

    /** Re-reads the live queue and repaints. Runs on the SWT thread only. */
    private void rebuild() {
        ensureListenerRegistered();

        ApprovalService service = currentService();
        boolean gateOn = ApprovalMode.getInstance().isOn();
        List<PendingProposalView> pending = service != null ? service.listAllPending() : List.of();
        ViewModel vm = ApprovalCardModel.viewModel(pending, gateOn);
        currentCards = vm.cards();

        // Drop interlock state for proposals that are no longer pending (approved/rejected/cleared),
        // so the set cannot grow unbounded while surviving rebuilds for still-pending cards.
        expandedProposalIds.retainAll(
                currentCards.stream().map(ApprovalCardModel::proposalId).toList());

        for (Control child : cardContainer.getChildren()) {
            child.dispose();
        }
        if (vm.state() == State.CARDS) {
            for (ApprovalCardModel card : currentCards) {
                buildCard(cardContainer, card);
            }
            stack.topControl = scroll;
        } else if (vm.state() == State.GATE_OFF) {
            stack.topControl = gateOffPanel;
        } else {
            stack.topControl = emptyPanel;
        }
        root.layout(true, true);
        relayoutCards();
        updateToolbarEnablement();
    }

    private void updateToolbarEnablement() {
        if (approveSafeButton == null || approveSafeButton.isDisposed()) {
            return;
        }
        int safe = ApprovalCardModel.safeApproveOrder(currentCards).size();
        int all = currentCards.size();
        approveSafeButton.setText("Approve all safe (" + safe + ")");
        approveSafeButton.setEnabled(safe > 0);
        approveAllButton.setText("Approve all (" + all + ") ⚠");
        approveAllButton.setEnabled(all > 0);
        // Button text width changed → re-lay the action bar so the labels are not clipped.
        headerBar.layout(true, true);
    }

    private void relayoutCards() {
        cardContainer.layout(true, true);
        int width = scroll.getClientArea().width;
        scroll.setMinSize(cardContainer.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT));
    }

    // ---- Card widget ----

    private void buildCard(Composite parent, ApprovalCardModel card) {
        Composite cardComp = new Composite(parent, SWT.BORDER);
        cardComp.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        cardComp.setLayout(layout);

        // Header: tool (left) + proposal id / age (right).
        Composite header = new Composite(cardComp, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        Label toolLabel = new Label(header, SWT.NONE);
        toolLabel.setText(card.tool());
        toolLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        Label metaLabel = new Label(header, SWT.NONE);
        metaLabel.setText(card.proposalId() + " · " + relativeTime(card.createdAt()));
        metaLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        // Effect rollup — destructive tokens in amber.
        Composite rollupRow = new Composite(cardComp, SWT.NONE);
        rollupRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        RowLayout rollupLayout = new RowLayout(SWT.HORIZONTAL);
        rollupLayout.marginLeft = 0;
        rollupLayout.marginTop = 2;
        rollupLayout.marginBottom = 2;
        rollupLayout.spacing = 2;
        rollupRow.setLayout(rollupLayout);
        List<RollupToken> tokens = card.rollup();
        for (int i = 0; i < tokens.size(); i++) {
            RollupToken token = tokens.get(i);
            Label tokenLabel = new Label(rollupRow, SWT.NONE);
            tokenLabel.setText(token.text());
            if (token.destructive()) {
                tokenLabel.setForeground(amber);
            }
            if (i < tokens.size() - 1) {
                new Label(rollupRow, SWT.NONE).setText("·");
            }
        }

        // Agent's-note slot — populated below the effect rollup, in a quieter register
        // (italic + dark-grey) so it never outranks the effect. Zero-height when absent.
        if (card.intentText() != null && !card.intentText().isBlank()) {
            Label intentLabel = new Label(cardComp, SWT.WRAP);
            intentLabel.setText("agent's note: " + card.intentText());
            intentLabel.setFont(intentFont);
            intentLabel.setForeground(cardComp.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            intentLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        }

        // Change rows. Single-op: the one row is permanently on-screen — no toggle to reveal it,
        // so the composite is shown by default. Multi-op: excluded/hidden until "Show changes".
        boolean singleOp = card.isSingleOp();
        Composite expanded = new Composite(cardComp, SWT.NONE);
        GridData expandedData = new GridData(SWT.FILL, SWT.TOP, true, false);
        expandedData.exclude = !singleOp;
        expanded.setLayoutData(expandedData);
        expanded.setVisible(singleOp);
        GridLayout expandedLayout = new GridLayout(1, false);
        expandedLayout.marginWidth = 0;
        expanded.setLayout(expandedLayout);
        for (ChangeRow row : card.rows()) {
            // The icon and text are two controls so they style independently — the kind
            // glyph takes the accent foreground while the effect text keeps the default, and each
            // part carries its own tooltip (icon / row). Still reads "<icon>␠␠<text>" on
            // one line; spacing0 RowLayout + the two literal spaces on the icon preserve the gap.
            Composite rowComp = new Composite(expanded, SWT.NONE);
            // Left-aligned at natural width (the inner RowLayout sizes the labels to their text and
            // ignores any granted width) — matches the original single Label's effective layout.
            rowComp.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
            rowLayout.marginLeft = 0;
            rowLayout.marginRight = 0;
            rowLayout.marginTop = 0;
            rowLayout.marginBottom = 0;
            rowLayout.spacing = 0;
            rowLayout.center = true;
            rowComp.setLayout(rowLayout);

            Label iconLabel = new Label(rowComp, SWT.NONE);
            iconLabel.setText(row.icon() + "  "); // keep the two-space gap (was inline in the Label)
            iconLabel.setToolTipText(ApprovalCardModel.kindLabelForIcon(row.icon())); // kind-name tooltip on the glyph

            Label textLabel = new Label(rowComp, SWT.NONE);
            textLabel.setText(row.text());
            textLabel.setToolTipText(row.text()); // full effect text on hover (truncation-proof)

            if (row.destructive()) {
                // Yield-to-amber: destructive rows paint BOTH parts amber, exactly as before — the
                // accent never appears on a destructive row (amber stays the only destructive signal).
                iconLabel.setForeground(amber);
                textLabel.setForeground(amber);
            } else {
                iconLabel.setForeground(accent); // uniform accent on the glyph; text stays default
            }
        }
        addTechnicalDetails(expanded, card.rawDetailsJson());

        // Buttons + interlock.
        Composite buttons = new Composite(cardComp, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout buttonLayout = new GridLayout(4, false);
        buttonLayout.marginWidth = 0;
        buttons.setLayout(buttonLayout);

        // Slot 1: a secondary, left-aligned "Copy JSON" button that puts the full proposal payload
        // on the clipboard. Read-only / human-side — it never approves, rejects, or mutates, and it
        // must NOT grab horizontal slack (that stays with the spacer/toggle below) so Reject/Approve
        // remain right-aligned.
        Button copyButton = new Button(buttons, SWT.PUSH);
        copyButton.setText("Copy JSON");
        copyButton.setToolTipText("Copy this proposal's full JSON to the clipboard");
        copyButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        final String cardCopyJson = card.copyJson();
        copyButton.addListener(SWT.Selection, e -> copyJsonToClipboard(copyButton, cardCopyJson));

        // Slot 2 of the 4-column buttons bar grabs the horizontal slack so Reject/Approve stay
        // right-aligned. Multi-op: the "Show changes" toggle Link fills it. Single-op:
        // there is no toggle — a leading spacer Label grabs the slack instead, mirroring the header bar.
        Link toggle = null;
        if (singleOp) {
            Label spacer = new Label(buttons, SWT.NONE);
            spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        } else {
            toggle = new Link(buttons, SWT.NONE);
            toggle.setText("<a>▾ Show changes</a>");
            toggle.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        }

        org.eclipse.swt.widgets.Button reject = new org.eclipse.swt.widgets.Button(buttons, SWT.PUSH);
        reject.setText("Reject");
        org.eclipse.swt.widgets.Button approve = new org.eclipse.swt.widgets.Button(buttons, SWT.PUSH);
        approve.setText("Approve");

        Label staleStrip = new Label(cardComp, SWT.WRAP);
        GridData staleData = new GridData(SWT.FILL, SWT.TOP, true, false);
        staleData.exclude = true;
        staleStrip.setLayoutData(staleData);
        staleStrip.setForeground(amber);

        // Per-card interlock: a card with a destructive op stays gated until expanded once. A single-op
        // card's one (amber, when destructive) row is permanently on-screen, so "review the changes
        // before approving" is satisfied by construction — auto-satisfy the interlock, Approve
        // enabled immediately. For multi-op the "expanded once" fact is seeded from the view-level set so
        // it survives a rebuild triggered by a *different* proposal arriving (no re-expand needed).
        boolean expandedOnce = singleOp || expandedProposalIds.contains(card.proposalId());
        applyApproveEnablement(approve, card, expandedOnce);

        if (toggle != null) {
            final Link toggleLink = toggle;
            toggleLink.addListener(SWT.Selection, e -> {
                // willShow == currently-excluded: clicking reveals the rows this time.
                boolean willShow = expandedData.exclude;
                expandedData.exclude = !willShow;
                expanded.setVisible(willShow);
                toggleLink.setText(willShow ? "<a>▴ Hide changes</a>" : "<a>▾ Show changes</a>");
                if (willShow) {
                    expandedProposalIds.add(card.proposalId());
                    applyApproveEnablement(approve, card, true);
                }
                relayoutCards();
            });
        }

        reject.addListener(SWT.Selection, e -> doReject(card));
        approve.addListener(SWT.Selection, e -> doApprove(card, approve, staleStrip, staleData));
    }

    /**
     * Copies the card's full proposal JSON onto the OS clipboard and gives brief visual
     * confirmation. Read-only and side-effect-free with respect to the approval queue: it never
     * touches {@link ApprovalService} or the model. The {@link Clipboard} is constructed, used, and
     * disposed per click so the view holds no clipboard resource.
     */
    private void copyJsonToClipboard(Button button, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        Clipboard clipboard = new Clipboard(button.getDisplay());
        try {
            clipboard.setContents(new Object[]{json}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
        // Lightweight confirmation: flip the label to "Copied", then restore after a short delay.
        // No relayout — "Copied" is narrower than "Copy JSON" so it fits the button's existing
        // bounds; skipping layout() avoids a width jump and any ScrolledComposite min-size reflow.
        // Runs on the SWT thread (selection listener), and the card is only rebuilt on that same
        // thread, so the button cannot be concurrently disposed; the timer callback still guards
        // isDisposed() for the case where a rebuild fires within the delay window.
        button.setText("Copied");
        button.getDisplay().timerExec(1500, () -> {
            if (!button.isDisposed()) {
                button.setText("Copy JSON");
            }
        });
    }

    private void addTechnicalDetails(Composite parent, String rawJson) {
        final Link link = new Link(parent, SWT.NONE);
        link.setText("<a>▸ Technical details (raw params)</a>");
        Text details = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        details.setText(rawJson != null ? rawJson : "");
        GridData detailsData = new GridData(SWT.FILL, SWT.TOP, true, false);
        detailsData.heightHint = 120;
        detailsData.exclude = true;
        details.setLayoutData(detailsData);
        link.addListener(SWT.Selection, e -> {
            boolean willShow = detailsData.exclude;
            detailsData.exclude = !willShow;
            details.setVisible(willShow);
            link.setText(willShow ? "<a>▾ Technical details (raw params)</a>"
                    : "<a>▸ Technical details (raw params)</a>");
            relayoutCards();
        });
    }

    private void applyApproveEnablement(org.eclipse.swt.widgets.Button approve,
            ApprovalCardModel card, boolean expandedOnce) {
        if (card.hasDestructive() && !expandedOnce) {
            approve.setEnabled(false);
            approve.setToolTipText("Review the changes first");
        } else {
            approve.setEnabled(true);
            approve.setToolTipText(null);
        }
    }

    // ---- Service calls ----

    private void doApprove(ApprovalCardModel card, org.eclipse.swt.widgets.Button approve,
            Label staleStrip, GridData staleData) {
        ApprovalService service = currentService();
        if (service == null) {
            return;
        }
        try {
            service.approve(card.sessionId(), card.proposalId());
            setStatus("Approved " + card.proposalId() + ".");
            rebuild();
        } catch (MutationException stale) {
            // Surface the staleness as a plain inline strip; leave the card so the human can Reject it.
            // Only Reject stays actionable. The reason now NAMES what the human touched — the
            // ProposalStalenessGuard / ProposalBuilder produce a plain-language, target-named message;
            // fall back to the generic line only if a message is somehow absent.
            logger.info("Proposal {} went stale on approve: {}", card.proposalId(), stale.getMessage());
            staleData.exclude = false;
            String reason = (stale.getMessage() != null && !stale.getMessage().isBlank())
                    ? stale.getMessage()
                    : "This proposal can no longer be applied because the model changed since the "
                            + "agent proposed it. Reject it and ask the agent to retry.";
            staleStrip.setText(reason);
            staleStrip.setVisible(true);
            approve.setEnabled(false);
            approve.setToolTipText("Proposal is stale — Reject and ask the agent to retry.");
            relayoutCards();
        }
    }

    private void doReject(ApprovalCardModel card) {
        ApprovalService service = currentService();
        if (service == null) {
            return;
        }
        // reject returns null when the proposal is already gone (race) — only claim success on a hit.
        if (service.reject(card.sessionId(), card.proposalId()) != null) {
            setStatus("Rejected " + card.proposalId() + " — no change to the model.");
        }
        rebuild();
    }

    /**
     * Approves a sequence of cards (oldest first), halting gracefully on the first stale proposal
     * Never approves a card the loop did not actually apply; reports what landed and what
     * remains via the view status line.
     */
    private void bulkApprove(List<ApprovalCardModel> order, boolean includesDestructive) {
        ApprovalService service = currentService();
        if (service == null || order.isEmpty()) {
            return;
        }
        int approved = 0;
        String stalledAt = null;
        for (ApprovalCardModel card : order) {
            try {
                service.approve(card.sessionId(), card.proposalId());
                approved++;
            } catch (MutationException stale) {
                stalledAt = card.proposalId();
                logger.info("Bulk approve halted at stale proposal {}: {}",
                        card.proposalId(), stale.getMessage());
                break;
            }
        }
        int remaining = order.size() - approved;
        if (stalledAt != null) {
            setStatus("Approved " + approved + " — stopped at " + stalledAt
                    + " because the model changed; " + remaining + " remain. Review the rest.");
        } else {
            setStatus("Approved " + approved + (includesDestructive ? " (including deletions)." : " safe change"
                    + (approved == 1 ? "." : "s.")));
        }
        rebuild();
    }

    private void setStatus(String message) {
        getViewSite().getActionBars().getStatusLineManager().setMessage(message);
    }

    private ApprovalService currentService() {
        return McpServerManager.getInstance().getApprovalService();
    }

    private void ensureListenerRegistered() {
        ApprovalService service = currentService();
        if (service == registeredService) {
            return;
        }
        if (registeredService != null) {
            registeredService.removeQueueListener(this);
        }
        if (service != null) {
            service.addQueueListener(this);
        }
        registeredService = service;
    }

    private static String relativeTime(String iso) {
        try {
            Duration age = Duration.between(Instant.parse(iso), Instant.now());
            long seconds = age.getSeconds();
            if (seconds < 10) {
                return "just now";
            }
            if (seconds < 60) {
                return seconds + "s ago";
            }
            if (seconds < 3600) {
                return (seconds / 60) + "m ago";
            }
            return (seconds / 3600) + "h ago";
        } catch (Exception e) {
            return "pending";
        }
    }

    @Override
    public void setFocus() {
        if (scroll != null && !scroll.isDisposed()) {
            scroll.setFocus();
        }
    }

    @Override
    public void dispose() {
        McpServerManager.getInstance().removeStateListener(this);
        ApprovalMode.getInstance().removeChangeListener(approvalModeListener);
        if (registeredService != null) {
            registeredService.removeQueueListener(this);
            registeredService = null;
        }
        if (amber != null && !amber.isDisposed()) {
            amber.dispose();
        }
        // accent is a shared system colour (getSystemColor) — must NOT be disposed. If it is
        // ever swapped to a managed `new Color(...)`, add its disposal here next to amber.
        if (intentFont != null && !intentFont.isDisposed()) {
            intentFont.dispose();
        }
        super.dispose();
    }
}
