package net.vheerden.archi.mcp.ui;

import java.io.IOException;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.fieldassist.FieldDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.McpPlugin;
import net.vheerden.archi.mcp.server.BearerTokenStore;
import net.vheerden.archi.mcp.server.CertificateGenerator;
import net.vheerden.archi.mcp.server.KeystorePasswordStore;
import net.vheerden.archi.mcp.server.OriginHostValidationHandler;

/**
 * Preferences page for MCP Server configuration.
 * Accessible via Window &gt; Preferences &gt; MCP Server.
 */
public class McpPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    private static final Logger logger = LoggerFactory.getLogger(McpPreferencePage.class);

    private static final String[][] LOG_LEVELS = {
        {"DEBUG", "DEBUG"},
        {"INFO", "INFO"},
        {"WARN", "WARN"},
        {"ERROR", "ERROR"}
    };

    /** Placeholder shown in the token field when no token has been generated yet. */
    private static final String TOKEN_PLACEHOLDER = "—";

    /**
     * Inline exposure-warning copy (amber). Names the stakes in user vocabulary: the concrete
     * consequence — every MCP tool becomes reachable by every device on the network — in plain
     * terms, not security jargon. Deliberately category-phrased with no hard-coded tool count
     * (the count is registry-derived and in flux; a literal here would rot). The closing sentence is
     * the progressive-disclosure affordance handed over from the bind-address exposure-warning:
     * it points the user at the Authentication section as a mitigation.
     */
    private static final String EXPOSURE_WARNING_TEXT =
            "This bind address exposes the MCP Server to other devices on your network. "
            + "Every MCP tool — including file read/write and model edit/deletion — becomes "
            + "reachable by any device that can reach this machine. Only bind to a non-loopback "
            + "address on a trusted network, or enable bearer-token authentication below to require "
            + "a secret on every request.";

    /**
     * Exposure copy once a token mitigates it (blue/INFORMATION). The bind is still non-loopback, but
     * every request now needs a valid token, so the framing softens from warning to advisory and
     * recommends TLS (the token travels in cleartext on a plain-HTTP LAN bind).
     */
    private static final String EXPOSURE_MITIGATED_TEXT =
            "This bind address exposes the MCP Server to other devices on your network, but "
            + "bearer-token authentication is enabled, so every request must present a valid token. "
            + "Consider enabling TLS below so the token is not sent in cleartext on the network.";

    /** Inline note under the Authentication section. */
    private static final String AUTH_SECTION_NOTE =
            "Generating or regenerating the token invalidates any client still using the old token — "
            + "you must update each client's Authorization header. On a non-loopback bind, also enable "
            + "TLS so the token is not sent in cleartext.";

    // Tri-state exposure banner: NONE (loopback), AMBER (exposed, no token mitigation),
    // BLUE (exposed but token-mitigated). Replaces the prior boolean so blue/amber/none transitions
    // stay idempotent across the multiple events a single keystroke or toggle can fire.
    private static final int EXPOSURE_NONE = 0;
    private static final int EXPOSURE_AMBER = 1;
    private static final int EXPOSURE_BLUE = 2;

    private IpAddressFieldEditor bindAddressEditor;
    private ControlDecoration bindExposureDecoration;
    /** Tracks which exposure banner is currently shown, so updates are idempotent. */
    private int exposureState = EXPOSURE_NONE;
    private BooleanFieldEditor tlsEnabledEditor;
    private FileFieldEditor keystorePathEditor;
    /**
     * Masked, secure-storage-backed keystore-password field. NOT a field editor —
     * it is not bound to any plain-store key; its value lives only in Equinox secure storage via
     * {@link KeystorePasswordStore}, loaded on open and persisted in {@link #performOk()}.
     */
    private Text keystorePasswordText;
    private Button generateButton;
    private Composite fieldParent;

    // --- Authentication section (token half of the auth audit finding) ---
    private BooleanFieldEditor authEnabledEditor;
    private Text tokenText;
    private Button copyButton;
    private Button regenerateButton;
    private Clipboard clipboard;
    /** Cached current token (or null), refreshed from secure storage; drives Copy/enablement. */
    private String currentToken;

    public McpPreferencePage() {
        super(GRID);
        setDescription("Configuration settings for the ArchiMate MCP Server.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, McpPlugin.PLUGIN_ID));
    }

    @Override
    protected void createFieldEditors() {
        Composite parent = getFieldEditorParent();
        this.fieldParent = parent;

        IntegerFieldEditor port = new IntegerFieldEditor(
                McpPlugin.PREF_PORT, "&Port:", parent, 5);
        port.setValidRange(1, 65535);
        addField(port);

        bindAddressEditor = new IpAddressFieldEditor(
                McpPlugin.PREF_BIND_ADDRESS, "&Bind address:", parent);
        addField(bindAddressEditor);

        // Warning field decoration on the bind-address text control — shown only when the bind is
        // a valid, non-loopback (exposed) value. Created hidden; toggled by updateExposureWarning().
        Text bindText = bindAddressEditor.getBindTextControl();
        if (bindText != null) {
            bindExposureDecoration = new ControlDecoration(bindText, SWT.LEFT | SWT.TOP);
            FieldDecoration warnDec = FieldDecorationRegistry.getDefault()
                    .getFieldDecoration(FieldDecorationRegistry.DEC_WARNING);
            bindExposureDecoration.setImage(warnDec.getImage());
            bindExposureDecoration.setDescriptionText(EXPOSURE_WARNING_TEXT);
            bindExposureDecoration.hide();
        }

        addField(new BooleanFieldEditor(
                McpPlugin.PREF_AUTO_START, "&Auto-start on launch", parent));

        addField(new ComboFieldEditor(
                McpPlugin.PREF_LOG_LEVEL, "&Log level:", LOG_LEVELS, parent));

        // --- TLS Section separator ---
        Label separator = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label tlsLabel = new Label(parent, SWT.NONE);
        tlsLabel.setText("TLS / HTTPS");
        tlsLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));

        tlsEnabledEditor = new BooleanFieldEditor(
                McpPlugin.PREF_TLS_ENABLED, "Enable &TLS (HTTPS)", parent);
        addField(tlsEnabledEditor);

        keystorePathEditor = new FileFieldEditor(
                McpPlugin.PREF_KEYSTORE_PATH, "&Keystore file:", parent);
        keystorePathEditor.setFileExtensions(new String[]{"*.p12", "*.pfx", "*.jks", "*.*"});
        addField(keystorePathEditor);

        // Keystore password: a MASKED, secure-storage-backed field. Unlike the other
        // TLS controls it is NOT a field editor — it is not bound to a plain-store pref key, so the
        // secret never lands in .metadata cleartext. Modelled on the bearer-token tokenRow
        // (a 3-col composite spanning the page), but EDITABLE + MASKED rather than read-only.
        Composite keystorePwRow = new Composite(parent, SWT.NONE);
        keystorePwRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        keystorePwRow.setLayout(new GridLayout(2, false));

        Label keystorePwCaption = new Label(keystorePwRow, SWT.NONE);
        keystorePwCaption.setText("Keystore password:");

        keystorePasswordText = new Text(keystorePwRow, SWT.PASSWORD | SWT.BORDER | SWT.SINGLE);
        keystorePasswordText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Load the current value on open: secure storage first, legacy-plain fallback for a
        // not-yet-migrated config. An unreadable store maps to empty + WARN, never a crash.
        setKeystorePasswordField(currentKeystorePasswordOrEmpty());

        // Generate Self-Signed Certificate button — own row spanning all columns
        Composite buttonRow = new Composite(parent, SWT.NONE);
        buttonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        buttonRow.setLayout(new GridLayout(1, false));
        generateButton = new Button(buttonRow, SWT.PUSH);
        generateButton.setText("Generate Self-Signed Certificate...");
        generateButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                generateSelfSignedCertificate();
            }
        });

        // --- Authentication Section (opt-in bearer token; mirrors the TLS section block) ---
        Label authSeparator = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        authSeparator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label authLabel = new Label(parent, SWT.NONE);
        authLabel.setText("Authentication");
        authLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));

        authEnabledEditor = new BooleanFieldEditor(
                McpPlugin.PREF_AUTH_TOKEN_ENABLED, "Enable bearer-token &authentication", parent);
        addField(authEnabledEditor);

        // Token display + Copy on one row (a 3-column composite spanning all page columns).
        Composite tokenRow = new Composite(parent, SWT.NONE);
        tokenRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        tokenRow.setLayout(new GridLayout(3, false));

        Label tokenCaption = new Label(tokenRow, SWT.NONE);
        tokenCaption.setText("Token:");

        // Read-only so the user can see/select/copy but never edit the secret in place.
        tokenText = new Text(tokenRow, SWT.READ_ONLY | SWT.BORDER | SWT.SINGLE);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        copyButton = new Button(tokenRow, SWT.PUSH);
        copyButton.setText("Cop&y");
        copyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                copyTokenToClipboard();
            }
        });

        // Generate / Regenerate button — own row, mirroring the certificate button.
        Composite authButtonRow = new Composite(parent, SWT.NONE);
        authButtonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        authButtonRow.setLayout(new GridLayout(1, false));
        regenerateButton = new Button(authButtonRow, SWT.PUSH);
        regenerateButton.setText("Generate / Regenerate token...");
        regenerateButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                generateOrRegenerateToken();
            }
        });

        Label authNote = new Label(parent, SWT.WRAP);
        authNote.setText(AUTH_SECTION_NOTE);
        GridData authNoteData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        authNoteData.widthHint = 380; // wrap to the page width rather than stretching the dialog
        authNote.setLayoutData(authNoteData);

        clipboard = new Clipboard(parent.getDisplay());
        refreshTokenDisplay();
    }

    @Override
    protected void initialize() {
        super.initialize();
        updateTlsFieldsEnabled();
        updateAuthFieldsEnabled();
        // Reflect the stored bind value on page open (warning present immediately if the
        // stored bind is already non-loopback; absent for the default 127.0.0.1).
        updateExposureWarning();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        if (event.getSource() == tlsEnabledEditor) {
            updateTlsFieldsEnabled();
        } else if (event.getSource() == authEnabledEditor) {
            // Ticking Enable with no token auto-generates one; disabling keeps the token
            // (re-enabling reuses it). Then refresh control enablement and re-evaluate the
            // exposure banner (auth-on with a token softens amber -> blue).
            if (Boolean.TRUE.equals(event.getNewValue()) && currentTokenOrNull() == null) {
                ensureTokenGenerated();
            }
            updateAuthFieldsEnabled();
            updateExposureWarning();
        } else if (event.getSource() == bindAddressEditor) {
            // Recompute on every bind-address change (value or validity). updateExposureWarning()
            // self-gates on validity, so partial/invalid input never shows the banner.
            updateExposureWarning();
        }
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        // Restore Defaults reloads the (loopback) default bind and the auth-disabled default; keep
        // the dependent UI in sync. The token itself is NOT cleared (it lives in secure storage, is
        // not a field-editor default) so re-enabling reuses it.
        refreshTokenDisplay();
        updateAuthFieldsEnabled();
        updateExposureWarning();
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        // Persist the keystore password to secure storage (never the plain store). super.performOk()
        // has already stored every FIELD EDITOR (port, path, TLS flag …); the password is not one of
        // them, so this is the only place it is written — and it goes to KeystorePasswordStore.
        // If the secure write fails, keep the dialog OPEN (return false) so the user is not misled into
        // thinking the password saved — they can Cancel to discard, or fix secure storage and retry.
        boolean passwordSaved = persistKeystorePassword();
        return ok && passwordSaved;
    }

    /**
     * Persists the masked field's value to secure storage and ensures the secret is not left in the
     * plain store: non-blank → {@link KeystorePasswordStore#set}; blank → clear. On a
     * durable success the legacy plain key is cleared too, so a {@code grep} of {@code .prefs} shows
     * no password regardless of migration timing. On a storage failure the legacy plain value is left
     * untouched (no data loss) and a value-free error dialog is shown.
     *
     * @return {@code true} if the value was persisted (or there is nothing to persist); {@code false}
     *         if a storage failure prevented the save (so {@link #performOk()} keeps the dialog open)
     */
    private boolean persistKeystorePassword() {
        if (keystorePasswordText == null || keystorePasswordText.isDisposed()) {
            return true;
        }
        String value = keystorePasswordText.getText();
        try {
            if (value == null || value.isBlank()) {
                KeystorePasswordStore.clear();
            } else {
                KeystorePasswordStore.set(value);
            }
        } catch (RuntimeException e) {
            // Leave the plain store exactly as it was (no data loss); surface a value-free error and
            // report failure so the prefs dialog does not silently close on an unsaved password.
            showKeystoreStorageError(e);
            return false;
        }
        // Durable secure write/clear confirmed → make sure the secret is not also in the plain store.
        clearLegacyPlainKeystorePassword();
        return true;
    }

    /**
     * Clears the legacy plain {@code PREF_KEYSTORE_PASSWORD} value from the instance-scope preference
     * store and flushes it to disk, so a {@code grep} sees nothing immediately after OK. Setting
     * it to the empty default removes the on-disk entry. Never writes a password value.
     */
    private void clearLegacyPlainKeystorePassword() {
        var store = getPreferenceStore();
        store.setValue(McpPlugin.PREF_KEYSTORE_PASSWORD, McpPlugin.DEFAULT_KEYSTORE_PASSWORD);
        if (store instanceof IPersistentPreferenceStore persistent && persistent.needsSaving()) {
            try {
                persistent.save();
            } catch (IOException e) {
                logger.warn("Could not flush the cleared legacy keystore password to the preference store", e);
            }
        }
    }

    /**
     * Reads the current keystore password for display: secure storage first, then a
     * legacy-plain fallback so a not-yet-migrated config still shows its value. An unreadable secure
     * store maps to the legacy/empty value with a WARN — never a crash, never an echoed value.
     *
     * @return the current password, or empty string if none is set or the store is unreadable
     */
    private String currentKeystorePasswordOrEmpty() {
        try {
            String pw = KeystorePasswordStore.get();
            if (pw != null && !pw.isBlank()) {
                return pw;
            }
        } catch (KeystorePasswordStore.KeystorePasswordStoreException e) {
            logger.warn("Could not read keystore password from secure storage for display", e);
        }
        String legacy = getPreferenceStore().getString(McpPlugin.PREF_KEYSTORE_PASSWORD);
        return legacy != null ? legacy : "";
    }

    /** Null/disposed-safe setter for the masked keystore-password field. */
    private void setKeystorePasswordField(String value) {
        if (keystorePasswordText != null && !keystorePasswordText.isDisposed()) {
            keystorePasswordText.setText(value != null ? value : "");
        }
    }

    private void showKeystoreStorageError(Throwable e) {
        // Log the full exception for diagnosis, but keep the dialog a fixed, value-free string:
        // a future/odd secure-storage layer could embed the value-that-failed-to-encrypt in its
        // message, so we never echo arbitrary throwable text here.
        logger.error("Keystore-password secure-storage operation failed", e);
        org.eclipse.jface.dialogs.MessageDialog.openError(
                getShell(),
                "Secure storage unavailable",
                "Could not store the keystore password. Secure storage could not be unlocked — its "
                + "OS-protected master password could not be obtained (e.g. a missing or locked entry "
                + "in the macOS Keychain or Windows Credential Store). Your existing TLS configuration "
                + "is unchanged. To reset secure storage, see \"Troubleshooting: secure storage\" in "
                + "the README. Full details are in the .metadata/.log file in your Archi workspace.");
    }

    @Override
    public void dispose() {
        // The ControlDecoration also self-disposes via the text control's dispose listener; this
        // makes the lifecycle explicit and releases the hover shell deterministically.
        if (bindExposureDecoration != null) {
            bindExposureDecoration.dispose();
            bindExposureDecoration = null;
        }
        if (clipboard != null) {
            clipboard.dispose();
            clipboard = null;
        }
        super.dispose();
    }

    /**
     * Shows the exposure warning (page-top banner + field decoration) <em>iff</em> the bind
     * address is a valid, non-loopback value — i.e. exactly when {@link OriginHostValidationHandler}
     * stops enforcing Origin/Host validation for that bind.
     *
     * <p>Self-gates on validity: a syntactically invalid bind is left to the existing
     * {@link IpAddressFieldEditor} error state and shows no exposure banner. Loopback literals
     * ({@code 127.x}, {@code localhost}) classify as safe instantly, so there is no transient
     * flicker while typing them. The exposure decision reuses the server's own
     * {@code isExposedBind} predicate so the warning can never drift from enforcement.</p>
     */
    private void updateExposureWarning() {
        if (bindAddressEditor == null) {
            return;
        }
        // getStringValue() reads the text control directly, so it reflects the current widget text
        // for both the VALUE and IS_VALID events a single keystroke may fire.
        String bind = bindAddressEditor.getStringValue();
        boolean exposed = IpAddressFieldEditor.isValidAddress(bind)
                && OriginHostValidationHandler.isExposedBind(bind);

        // Tri-state: none if loopback; blue if exposed-but-token-mitigated; amber otherwise.
        int newState = !exposed ? EXPOSURE_NONE : (isTokenMitigated() ? EXPOSURE_BLUE : EXPOSURE_AMBER);

        // Idempotent: only touch the page message channel when the state actually flips. A keystroke
        // can fire both VALUE and IS_VALID events, and an auth toggle re-enters here too; without
        // this guard each recompute would needlessly disturb the page description.
        if (newState == exposureState) {
            return;
        }
        exposureState = newState;

        switch (newState) {
            case EXPOSURE_AMBER -> setMessage(EXPOSURE_WARNING_TEXT, IMessageProvider.WARNING);
            case EXPOSURE_BLUE -> setMessage(EXPOSURE_MITIGATED_TEXT, IMessageProvider.INFORMATION);
            // Clear only our banner. This does not touch the validator's error message
            // (setErrorMessage is a separate channel that takes display precedence), so the validator wins.
            default -> setMessage(null, IMessageProvider.NONE);
        }
        if (bindExposureDecoration != null) {
            // The field-level warning icon marks an UNMITIGATED exposure only; once a token mitigates
            // it (blue), hide the warning decoration so it does not contradict the advisory banner.
            if (newState == EXPOSURE_AMBER) {
                bindExposureDecoration.show();
            } else {
                bindExposureDecoration.hide();
            }
        }
    }

    private void updateTlsFieldsEnabled() {
        boolean enabled = tlsEnabledEditor.getBooleanValue();
        keystorePathEditor.setEnabled(enabled, fieldParent);
        setControlEnabled(keystorePasswordText, enabled);
        if (generateButton != null && !generateButton.isDisposed()) {
            generateButton.setEnabled(enabled);
        }
    }

    /**
     * Enables/disables the token controls off the auth checkbox (mirrors
     * {@link #updateTlsFieldsEnabled()}). Copy is additionally gated on a token actually existing.
     */
    private void updateAuthFieldsEnabled() {
        if (authEnabledEditor == null) {
            return;
        }
        boolean enabled = authEnabledEditor.getBooleanValue();
        setControlEnabled(tokenText, enabled);
        setControlEnabled(regenerateButton, enabled);
        setControlEnabled(copyButton, enabled && currentToken != null);
    }

    /** True when the exposure is mitigated by an enabled-and-set token (drives amber→blue, Task 6). */
    private boolean isTokenMitigated() {
        return authEnabledEditor != null
                && authEnabledEditor.getBooleanValue()
                && currentTokenOrNull() != null;
    }

    /**
     * Reads the current token from secure storage, caching it and updating the read-only display
     * (the actual token when one exists, the {@value #TOKEN_PLACEHOLDER} placeholder otherwise).
     */
    private void refreshTokenDisplay() {
        currentToken = currentTokenOrNull();
        if (tokenText != null && !tokenText.isDisposed()) {
            tokenText.setText(currentToken != null ? currentToken : TOKEN_PLACEHOLDER);
        }
    }

    /**
     * Reads the stored token, mapping a missing/unreadable store to {@code null} (the UI shows the
     * placeholder rather than crashing). A genuine storage failure is logged and surfaced to the
     * user only on a write/generate action, never on a passive read.
     *
     * @return the token, or null if none is set or the store is unreadable
     */
    private String currentTokenOrNull() {
        try {
            String token = BearerTokenStore.getToken();
            return (token != null && !token.isBlank()) ? token : null;
        } catch (BearerTokenStore.BearerTokenStoreException e) {
            logger.warn("Could not read bearer token from secure storage for display", e);
            return null;
        }
    }

    /** Generates and persists a token (used by the auto-generate-on-enable path), refreshing the UI. */
    private void ensureTokenGenerated() {
        try {
            BearerTokenStore.setToken(BearerTokenStore.generate());
        } catch (RuntimeException e) {
            showStorageError(e);
        }
        refreshTokenDisplay();
    }

    /**
     * Generate-or-regenerate button handler. With no token, generates one. With an existing
     * token, confirms first (regeneration is destructive — it invalidates existing clients), matching
     * the certificate-generation modal-confirm precedent.
     */
    private void generateOrRegenerateToken() {
        boolean hasToken = currentTokenOrNull() != null;
        if (hasToken) {
            boolean ok = org.eclipse.jface.dialogs.MessageDialog.openConfirm(
                    getShell(),
                    "Regenerate token?",
                    "Regenerating replaces the current bearer token. Every client still using the old "
                    + "token will be rejected (401) until you update its Authorization header.\n\n"
                    + "Continue?");
            if (!ok) {
                return;
            }
        }
        try {
            BearerTokenStore.setToken(BearerTokenStore.generate());
        } catch (RuntimeException e) {
            showStorageError(e);
            // Re-sync the display with whatever is actually in storage (the write failed, so the
            // prior token is unchanged) — keeps this path consistent with ensureTokenGenerated().
            refreshTokenDisplay();
            updateAuthFieldsEnabled();
            return;
        }
        refreshTokenDisplay();
        updateAuthFieldsEnabled();
        updateExposureWarning();
    }

    /** Copies the current token to the system clipboard. No-op when no token is set. */
    private void copyTokenToClipboard() {
        if (currentToken == null || clipboard == null) {
            return;
        }
        clipboard.setContents(new Object[]{currentToken}, new Transfer[]{TextTransfer.getInstance()});
    }

    private void showStorageError(Throwable e) {
        // Log the full exception (with cause) for diagnosis, but do NOT echo the underlying
        // exception's getMessage() into the user-visible dialog: a future/odd secure-storage layer
        // could embed the value-that-failed-to-encrypt in its message, and surfacing arbitrary
        // throwable text here would risk leaking it. The dialog stays a fixed, token-free string.
        logger.error("Bearer-token secure-storage operation failed", e);
        org.eclipse.jface.dialogs.MessageDialog.openError(
                getShell(),
                "Secure storage unavailable",
                "Could not store the bearer token. Secure storage could not be unlocked — its "
                + "OS-protected master password could not be obtained (e.g. a missing or locked entry "
                + "in the macOS Keychain or Windows Credential Store). Authentication cannot be "
                + "configured until secure storage is available. To reset secure storage, see "
                + "\"Troubleshooting: secure storage\" in the README. Full details are in the "
                + ".metadata/.log file in your Archi workspace.");
    }

    /** Null-safe, disposed-safe enable toggle for a plain SWT control. */
    private static void setControlEnabled(Control control, boolean enabled) {
        if (control != null && !control.isDisposed()) {
            control.setEnabled(enabled);
        }
    }

    private void generateSelfSignedCertificate() {
        Display display = Display.getCurrent();
        try {
            CertificateGenerator.Result result = CertificateGenerator.generate();

            // Auto-populate the keystore path (a field editor) and the masked password field.
            keystorePathEditor.setStringValue(result.keystorePath());
            setKeystorePasswordField(result.password());
            // Cert-gen is a deliberate one-shot action: persist the generated password to
            // secure storage immediately so a subsequent TLS start finds it, and clear any
            // legacy plain value so the secret is never in cleartext. A storage failure surfaces
            // a value-free dialog; the path/field stay set so the user can retry OK.
            try {
                KeystorePasswordStore.set(result.password());
                clearLegacyPlainKeystorePassword();
            } catch (RuntimeException storageEx) {
                showKeystoreStorageError(storageEx);
            }

            org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    display.getActiveShell(),
                    "Certificate Generated",
                    "Self-signed certificate generated successfully.\n\n"
                    + "Keystore: " + result.keystorePath() + "\n"
                    + "Valid for 365 days.\n\n"
                    + "Enable TLS and restart the server to use HTTPS.");

            logger.info("Self-signed certificate generated at {}", result.keystorePath());
        } catch (Exception ex) {
            // Do NOT echo ex.getMessage() into the dialog: the message can carry values this code
            // does not control. A keytool failure folds the child process's own output into the
            // IOException, and process-launch failures can quote the offending argument or
            // environment value back at us. Neither is text this code shapes or bounds, so the
            // dialog stays a fixed, value-free string and the detail goes to the log.
            logger.error("Failed to generate self-signed certificate", ex);
            org.eclipse.jface.dialogs.MessageDialog.openError(
                    display.getActiveShell(),
                    "Certificate Generation Failed",
                    "Could not generate the self-signed certificate. Check the .metadata/.log file in "
                    + "your Archi workspace for details.");
        }
    }
}
