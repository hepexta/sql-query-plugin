/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.sqlquery.plugin.db.ConnectionProfile;
import com.sqlquery.plugin.db.DriverResolver;
import com.sqlquery.plugin.settings.SqlQuerySettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for the saved connection profiles.
 *
 * <p>Changes are written back to {@link SqlQuerySettings} and the credential store only when
 * the dialog is confirmed, so cancelling leaves the configuration untouched.</p>
 */
public final class ConnectionProfilesDialog extends DialogWrapper {

    private static final String[] SSL_MODES = {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"};

    private final Project project;

    private final DefaultListModel<ConnectionProfile> listModel = new DefaultListModel<>();
    private final JBList<ConnectionProfile> profileList = new JBList<>(listModel);

    private final JBTextField nameField = new JBTextField();
    private final JBTextField hostField = new JBTextField();
    private final JBTextField portField = new JBTextField();
    private final JBTextField databaseField = new JBTextField();
    private final JBTextField userField = new JBTextField();
    private final com.intellij.openapi.ui.ComboBox<String> sslCombo = new ComboBox<>(SSL_MODES);
    /**
     * Masked: this field holds a database password, and it is filled with the value read back
     * from the credential store when a profile is selected. A plain text field would show it to
     * anyone looking at the screen — and the password is one of the few values here that the
     * user cannot check by eye in a log or a copy of the file.
     */
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBTextField urlOverrideField = new JBTextField();
    private final JBTextField driverPathField = new JBTextField();
    private final JBTextField timeoutField = new JBTextField();
    private final JBTextField fetchLimitField = new JBTextField();
    private final JBCheckBox storePasswordCheckBox = new JBCheckBox("Store password in the IDE credential store");
    private final JBCheckBox confirmDestructiveCheckBox =
            new JBCheckBox("Ask before running DROP / TRUNCATE / DELETE statements");
    private final JBTextArea statusArea = new JBTextArea(2, 40);

    /** Passwords typed in this dialog, keyed by profile name; applied on OK. */
    private final java.util.Map<String, String> pendingPasswords = new java.util.HashMap<>();

    private boolean loading;

    public ConnectionProfilesDialog(@Nullable Project project) {
        super(project, true);
        this.project = project;
        setTitle("Simple SQL Query \u2014 Connections");
        setResizable(true);

        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setOpaque(false);
        statusArea.setBorder(null);
        statusArea.setFont(JBUI.Fonts.label());

        loadProfiles();
        init();
        updateFieldsFromSelection(false);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel left = createProfileListPanel();
        JPanel right = createFormPanel();

        JPanel root = new JPanel(new BorderLayout(12, 0));
        root.add(left, BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(720, 420));
        return root;
    }

    private @NotNull JPanel createProfileListPanel() {
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setCellRenderer(SimpleListCellRenderer.create("", ConnectionProfile::getName));
        profileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateFieldsFromSelection(true);
            }
        });

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(profileList)
                .setAddAction(button -> addProfile())
                .setRemoveAction(button -> removeProfile())
                .setEditAction(button -> renameSelected())
                .disableUpDownActions();
        JPanel panel = decorator.createPanel();
        panel.setPreferredSize(new Dimension(200, 300));
        return panel;
    }

    private @NotNull JPanel createFormPanel() {
        FormBuilder builder = FormBuilder.createFormBuilder()
                .addLabeledComponent("Name:", nameField)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portField)
                .addLabeledComponent("Database:", databaseField)
                .addLabeledComponent("User:", userField)
                .addLabeledComponent("Password:", passwordField)
                .addLabeledComponent("SSL mode:", sslCombo)
                .addLabeledComponent("JDBC URL override:", urlOverrideField)
                .addLabeledComponent("Driver jar:", driverPathField)
                .addLabeledComponent("Statement timeout (s):", timeoutField)
                .addLabeledComponent("Row fetch limit:", fetchLimitField)
                .addComponent(storePasswordCheckBox)
                .addComponent(confirmDestructiveCheckBox)
                .addComponent(new JBLabel(" "))
                .addComponent(new JBScrollPane(statusArea));

        JPanel form = builder.getPanel();
        form.setBorder(JBUI.Borders.empty(4));

        JBLabel hint = new JBLabel("<html><body style='width:420px'>"
                + "Leave <b>Driver jar</b> empty to use the PostgreSQL driver bundled with IntelliJ IDEA. "
                + "If the IDE cannot supply one, download <code>postgresql-&lt;version&gt;.jar</code> from "
                + "<a href=\"https://jdbc.postgresql.org/download/\">jdbc.postgresql.org</a> and point this "
                + "field at the jar (or at the folder containing it)."
                + "</body></html>");
        hint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);

        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.add(form, BorderLayout.CENTER);
        wrapper.add(hint, BorderLayout.SOUTH);
        return wrapper;
    }

    @Override
    protected @Nullable JComponent createSouthPanel() {
        JComponent south = super.createSouthPanel();
        JButton test = new JButton("Test Connection");
        test.addActionListener(e -> testConnection());

        JPanel panel = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        left.add(test);
        panel.add(left, BorderLayout.WEST);
        panel.add(south, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            return new ValidationInfo("Port must be a number.", portField);
        }
        try {
            Integer.parseInt(timeoutField.getText().trim());
        } catch (NumberFormatException ex) {
            return new ValidationInfo("Statement timeout must be a number of seconds.", timeoutField);
        }
        try {
            Integer.parseInt(fetchLimitField.getText().trim());
        } catch (NumberFormatException ex) {
            return new ValidationInfo("Row fetch limit must be a number.", fetchLimitField);
        }
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Name is required.", nameField);
        }
        ConnectionProfile candidate = collectFromFields();
        String problem = candidate.validationError();
        if (problem != null) {
            return new ValidationInfo(problem, databaseField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        ConnectionProfile edited = collectFromFields();
        if (listModel.isEmpty()) {
            return;
        }
        // Commit the edits of the currently selected profile before saving everything.
        stashFieldEdits();

        SqlQuerySettings settings = SqlQuerySettings.getInstance();
        List<ConnectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            profiles.add(listModel.get(i));
        }
        settings.replaceProfiles(profiles);

        for (ConnectionProfile profile : profiles) {
            String password = pendingPasswords.get(profile.getName());
            if (password != null) {
                settings.setPassword(profile.getName(), profile.isStorePassword() ? password : null);
            } else if (!profile.isStorePassword()) {
                settings.setPassword(profile.getName(), null);
            }
        }
        settings.setConfirmDestructiveStatements(confirmDestructiveCheckBox.isSelected());
        if (settings.findProfile(edited.getName()) != null) {
            settings.setActiveProfile(edited.getName());
        }
        super.doOKAction();
    }

    // ------------------------------------------------------------------ loading

    private void loadProfiles() {
        SqlQuerySettings settings = SqlQuerySettings.getInstance();
        for (ConnectionProfile profile : settings.getProfiles()) {
            listModel.addElement(ConnectionProfile.copyOf(profile));
        }
        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(0);
        }
        confirmDestructiveCheckBox.setSelected(settings.isConfirmDestructiveStatements());
    }

    private void updateFieldsFromSelection(boolean keepPassword) {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            return;
        }
        loading = true;
        try {
            nameField.setText(selected.getName());
            hostField.setText(selected.getHost());
            portField.setText(String.valueOf(selected.getPort()));
            databaseField.setText(selected.getDatabase());
            userField.setText(selected.getUser());
            sslCombo.setSelectedItem(selected.getSslMode());
            urlOverrideField.setText(selected.getUrlOverride());
            driverPathField.setText(selected.getDriverPath());
            timeoutField.setText(String.valueOf(selected.getStatementTimeoutSeconds()));
            fetchLimitField.setText(String.valueOf(selected.getFetchLimit()));
            storePasswordCheckBox.setSelected(selected.isStorePassword());

            String password = pendingPasswords.get(selected.getName());
            if (password == null) {
                password = SqlQuerySettings.getInstance().getPassword(selected.getName());
            }
            passwordField.setText(password == null ? "" : password);
        } finally {
            loading = false;
        }
    }

    /** Applies the field values to the selected profile without touching the model identity. */
    private void stashFieldEdits() {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null || loading) {
            return;
        }
        String previousName = selected.getName();
        applyFieldsTo(selected);
        String newName = selected.getName();
        if (!previousName.equals(newName)) {
            // Keep the typed password attached to the renamed profile.
            String pending = pendingPasswords.remove(previousName);
            if (pending != null) {
                pendingPasswords.put(newName, pending);
            }
            profileList.repaint();
        }
        pendingPasswords.put(newName, new String(passwordField.getPassword()));
    }

    private void applyFieldsTo(@NotNull ConnectionProfile profile) {
        profile.setName(nameField.getText().trim());
        profile.setHost(hostField.getText().trim());
        profile.setPort(parseInt(portField.getText(), ConnectionProfile.DEFAULT_PORT));
        profile.setDatabase(databaseField.getText().trim());
        profile.setUser(userField.getText().trim());
        Object ssl = sslCombo.getSelectedItem();
        profile.setSslMode(ssl == null ? "prefer" : ssl.toString());
        profile.setUrlOverride(urlOverrideField.getText().trim());
        profile.setDriverPath(driverPathField.getText().trim());
        profile.setStatementTimeoutSeconds(parseInt(timeoutField.getText(), 30));
        profile.setFetchLimit(parseInt(fetchLimitField.getText(), 500));
        profile.setStorePassword(storePasswordCheckBox.isSelected());
    }

    private @NotNull ConnectionProfile collectFromFields() {
        ConnectionProfile profile = new ConnectionProfile();
        applyFieldsTo(profile);
        return profile;
    }

    private static int parseInt(@NotNull String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- list edits

    private void addProfile() {
        stashFieldEdits();
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(uniqueName("New connection"));
        profile.setHost("localhost");
        profile.setDatabase("postgres");
        profile.setUser("postgres");
        listModel.addElement(profile);
        profileList.setSelectedValue(profile, true);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private @NotNull String uniqueName(@NotNull String base) {
        String candidate = base;
        int suffix = 1;
        while (findByName(candidate) != null) {
            candidate = base + " " + (++suffix);
        }
        return candidate;
    }

    private @Nullable ConnectionProfile findByName(@NotNull String name) {
        for (int i = 0; i < listModel.size(); i++) {
            if (name.equals(listModel.get(i).getName())) {
                return listModel.get(i);
            }
        }
        return null;
    }

    private void removeProfile() {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            return;
        }
        int index = profileList.getSelectedIndex();
        listModel.remove(index);
        pendingPasswords.remove(selected.getName());
        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(Math.min(index, listModel.size() - 1));
        }
    }

    private void renameSelected() {
        ConnectionProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String name = Messages.showInputDialog(project, "Connection name:", "Rename Connection",
                Messages.getQuestionIcon(), selected.getName(), null);
        if (name == null || name.isBlank()) {
            return;
        }
        if (findByName(name.trim()) != null && !name.trim().equals(selected.getName())) {
            Messages.showWarningDialog(project, "A connection named \"" + name.trim() + "\" already exists.",
                    "Simple SQL Query");
            return;
        }
        String previous = selected.getName();
        selected.setName(name.trim());
        String pending = pendingPasswords.remove(previous);
        if (pending != null) {
            pendingPasswords.put(selected.getName(), pending);
        }
        profileList.repaint();
        if (selected == profileList.getSelectedValue()) {
            nameField.setText(selected.getName());
        }
    }

    // -------------------------------------------------------------------- tests

    private void testConnection() {
        stashFieldEdits();
        ConnectionProfile candidate = collectFromFields();
        String problem = candidate.validationError();
        if (problem != null) {
            statusArea.setText(problem);
            return;
        }
        String password = new String(passwordField.getPassword());
        statusArea.setText("Connecting to " + candidate.describeTarget() + " \u2026");

        new Task.Backgroundable(project, "Testing connection", false) {
            private String message;
            private boolean ok;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (Connection connection = DriverResolver.open(candidate, password)) {
                    ok = true;
                    message = "Connection successful.\n" + connection.getMetaData().getDatabaseProductName()
                            + " " + connection.getMetaData().getDatabaseProductVersion()
                            + "\nDriver: " + connection.getMetaData().getDriverName()
                            + " " + connection.getMetaData().getDriverVersion();
                } catch (Exception e) {
                    ok = false;
                    message = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                statusArea.setText(message);
                ApplicationManager.getApplication().invokeLater(() -> {
                    statusArea.setForeground(ok
                            ? com.intellij.ui.JBColor.namedColor("Label.foreground", JBUI.CurrentTheme.Label.foreground())
                            : com.intellij.ui.JBColor.RED);
                });
            }
        }.queue();
    }
}
