package net.vheerden.archi.mcp.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.McpPlugin;
import net.vheerden.archi.mcp.server.CertificateGenerator;

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

    private BooleanFieldEditor tlsEnabledEditor;
    private FileFieldEditor keystorePathEditor;
    private StringFieldEditor keystorePasswordEditor;
    private Button generateButton;
    private Composite fieldParent;

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

        addField(new IpAddressFieldEditor(
                McpPlugin.PREF_BIND_ADDRESS, "&Bind address:", parent));

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

        keystorePasswordEditor = new StringFieldEditor(
                McpPlugin.PREF_KEYSTORE_PASSWORD, "Keystore &password:", parent);
        addField(keystorePasswordEditor);

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
    }

    @Override
    protected void initialize() {
        super.initialize();
        updateTlsFieldsEnabled();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        if (event.getSource() == tlsEnabledEditor) {
            updateTlsFieldsEnabled();
        }
    }

    private void updateTlsFieldsEnabled() {
        boolean enabled = tlsEnabledEditor.getBooleanValue();
        keystorePathEditor.setEnabled(enabled, fieldParent);
        keystorePasswordEditor.setEnabled(enabled, fieldParent);
        if (generateButton != null && !generateButton.isDisposed()) {
            generateButton.setEnabled(enabled);
        }
    }

    private void generateSelfSignedCertificate() {
        Display display = Display.getCurrent();
        try {
            CertificateGenerator.Result result = CertificateGenerator.generate();

            // Auto-populate the preference fields
            keystorePathEditor.setStringValue(result.keystorePath());
            keystorePasswordEditor.setStringValue(result.password());

            org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    display.getActiveShell(),
                    "Certificate Generated",
                    "Self-signed certificate generated successfully.\n\n"
                    + "Keystore: " + result.keystorePath() + "\n"
                    + "Valid for 365 days.\n\n"
                    + "Enable TLS and restart the server to use HTTPS.");

            logger.info("Self-signed certificate generated at {}", result.keystorePath());
        } catch (Exception ex) {
            logger.error("Failed to generate self-signed certificate", ex);
            org.eclipse.jface.dialogs.MessageDialog.openError(
                    display.getActiveShell(),
                    "Certificate Generation Failed",
                    "Could not generate self-signed certificate:\n" + ex.getMessage());
        }
    }
}
