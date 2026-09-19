/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.sqlquery.plugin.db.ConnectionProfile;
import com.sqlquery.plugin.db.SqlSplitter;
import com.sqlquery.plugin.db.StatementResult;
import com.sqlquery.plugin.execute.QueryExecutor;
import com.sqlquery.plugin.settings.SqlQuerySettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

/**
 * The Simple SQL Query tool window: a connection bar, a SQL editor with results below it,
 * and a status line.
 */
public final class SqlQueryPanel extends SimpleToolWindowPanel implements com.intellij.openapi.Disposable {

    private final Project project;
    private final QueryExecutor executor = new QueryExecutor();

    private final JBTabbedPane resultsTabs = new JBTabbedPane();
    private final JBLabel statusLabel = new JBLabel("Not connected");
    private final JBLabel resultLabel = new JBLabel(" ");

    private final com.intellij.openapi.ui.ComboBox<ConnectionProfile> profileCombo =
            new com.intellij.openapi.ui.ComboBox<>();
    private final com.intellij.openapi.ui.ComboBox<String> databaseCombo =
            new com.intellij.openapi.ui.ComboBox<>();
    private final JButton connectButton = new JButton("Connect");

    private Editor editor;
    private JComponent editorToolbarComponent;
    private JComponent editorWrapper;
    private DefaultActionGroup editorActionGroup;
    private boolean syncingDatabaseCombo;

    public SqlQueryPanel(@NotNull Project project, @NotNull Disposable parentDisposable) {
        // SimpleToolWindowPanel places setToolbar(...) on the NORTH edge when this flag is true
        // and on the WEST edge when it is false. We want the connection bar across the top, so
        // the flag has to be true - passing false is what pushed the bar down the left edge.
        super(true);
        this.project = project;

        createEditor();
        setToolbar(createConnectionBar());
        setContent(createContent());
        registerShortcuts();
        refreshProfileCombo();
        updateConnectionStateUi();

        // Release the editor and the JDBC session with the tool window.
        Disposer.register(parentDisposable, this);
    }

    @Override
    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        executor.disconnect();
    }

    // ------------------------------------------------------------------ creation

    /** Exposes the editor to tests; not part of the plugin's public surface. */
    public @Nullable Editor getEditorForTests() {
        return editor;
    }

    /** Exposes the Run/Stop toolbar strip to tests; not part of the plugin's public surface. */
    public @Nullable JComponent getEditorToolbarComponentForTests() {
        return editorToolbarComponent;
    }

    /** Exposes the editor container to tests; not part of the plugin's public surface. */
    public @Nullable JComponent getEditorWrapperForTests() {
        return editorWrapper;
    }

    /** Exposes the Run/Stop action group to tests; not part of the plugin's public surface. */
    public @Nullable DefaultActionGroup getEditorActionGroupForTests() {
        return editorActionGroup;
    }

    private void createEditor() {
        Document document = EditorFactory.getInstance().createDocument(defaultScript());
        // isViewer = false: the editor must be editable so queries can be typed into it.
        editor = EditorFactory.getInstance().createEditor(document, project, PlainTextFileType.INSTANCE, false);
        EditorSettings settings = editor.getSettings();
        settings.setLineNumbersShown(true);
        settings.setLineMarkerAreaShown(false);
        settings.setFoldingOutlineShown(false);
        settings.setUseSoftWraps(true);
        settings.setAdditionalLinesCount(2);
        settings.setAdditionalColumnsCount(2);
        settings.setRightMarginShown(false);
        settings.setCaretRowShown(true);
        editor.getContentComponent().setFont(monospacedEditorFont());
    }

    private static @NotNull Font monospacedEditorFont() {
        try {
            return EditorColorsManager.getInstance().getGlobalScheme().getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN);
        } catch (Throwable t) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }
    }

    private static @NotNull String defaultScript() {
        return "-- Simple SQL Query \u2014 press Ctrl+Enter to run the statement at the caret\n"
                + "-- or select several statements to run them all\n"
                + "select version();\n";
    }

    /**
     * The connection bar across the top of the tool window. It is installed as the panel's
     * horizontal toolbar, so it spans the full width above the editor and the results.
     */
    private @NotNull JComponent createConnectionBar() {
        JPanel bar = new JBPanel<>();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(JBUI.Borders.empty(4, 6));

        profileCombo.setPreferredSize(new Dimension(190, profileCombo.getPreferredSize().height));
        profileCombo.setMaximumSize(new Dimension(220, profileCombo.getPreferredSize().height));
        profileCombo.addActionListener(e -> onProfileSelected());

        connectButton.addActionListener(e -> toggleConnection());

        JButton manageButton = new JButton("Manage\u2026");
        manageButton.addActionListener(e -> openConnectionDialog());

        bar.add(new JBLabel("Connection:"));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(profileCombo);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(connectButton);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(manageButton);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(new JBLabel("Database:"));
        bar.add(Box.createHorizontalStrut(4));
        databaseCombo.setPreferredSize(new Dimension(160, databaseCombo.getPreferredSize().height));
        databaseCombo.setMaximumSize(new Dimension(200, databaseCombo.getPreferredSize().height));
        databaseCombo.setEnabled(false);
        databaseCombo.addActionListener(e -> onDatabaseSelected());
        bar.add(databaseCombo);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private @NotNull JComponent createContent() {
        JBSplitter splitter = new OnePixelSplitter(true, 0.42f);
        splitter.setFirstComponent(createEditorComponent());
        splitter.setSecondComponent(createResultsComponent());
        splitter.setHonorComponentsMinimumSize(true);

        JPanel content = new JBPanel<>(new BorderLayout());
        content.add(splitter, BorderLayout.CENTER);
        content.add(createStatusBar(), BorderLayout.SOUTH);
        return content;
    }

    /** The SQL editor with the execution toolbar directly above it. */
    private @NotNull JComponent createEditorComponent() {
        JPanel wrapper = new JBPanel<>(new BorderLayout());
        wrapper.add(createEditorToolbar(), BorderLayout.NORTH);

        JScrollPane scrollPane = new JBScrollPane(editor.getComponent());
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        wrapper.add(scrollPane, BorderLayout.CENTER);
        wrapper.setMinimumSize(new Dimension(0, 80));
        editorWrapper = wrapper;
        return wrapper;
    }

    /** Run / Stop / transaction controls, laid out horizontally above the editor. */
    private @NotNull JComponent createEditorToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RunCurrentStatementAction());
        group.add(new RunSelectionOrAllAction());
        group.add(Separator.getInstance());
        group.add(new StopAction());
        group.add(Separator.getInstance());
        group.add(new ToggleAutoCommitAction());
        group.add(new CommitAction());
        group.add(new RollbackAction());
        group.add(Separator.getInstance());
        group.add(new ClearResultsAction());

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("SimpleSqlQuery.Editor", group, true);
        toolbar.setTargetComponent(this);
        editorActionGroup = group;
        editorToolbarComponent = toolbar.getComponent();
        editorToolbarComponent.setBorder(JBUI.Borders.empty(2, 4));
        return editorToolbarComponent;
    }

    private @NotNull JComponent createResultsComponent() {
        resultsTabs.setBorder(JBUI.Borders.empty());
        return resultsTabs;
    }

    private @NotNull JComponent createStatusBar() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(3, 6));
        panel.add(statusLabel, BorderLayout.WEST);
        resultLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        panel.add(resultLabel, BorderLayout.EAST);
        return panel;
    }

    /**
     * Ctrl/Cmd+Enter runs the statement at the caret, Ctrl/Cmd+Shift+Enter runs everything.
     *
     * <p>Registered on the editor component via {@link CustomShortcutSet}, which handles the
     * Cmd/Control differences between Windows, Linux and macOS.</p>
     */
    private void registerShortcuts() {
        JComponent component = editor.getContentComponent();

        RunCurrentStatementAction runCurrent = new RunCurrentStatementAction();
        runCurrent.registerCustomShortcutSet(
                new CustomShortcutSet(KeyboardShortcut.fromString("ctrl ENTER")), component);
        runCurrent.registerCustomShortcutSet(
                new CustomShortcutSet(KeyboardShortcut.fromString("meta ENTER")), component);

        RunSelectionOrAllAction runAll = new RunSelectionOrAllAction();
        runAll.registerCustomShortcutSet(
                new CustomShortcutSet(KeyboardShortcut.fromString("ctrl shift ENTER")), component);
        runAll.registerCustomShortcutSet(
                new CustomShortcutSet(KeyboardShortcut.fromString("meta shift ENTER")), component);
    }

    // -------------------------------------------------------------- connection

    private void refreshProfileCombo() {
        SqlQuerySettings settings = SqlQuerySettings.getInstance();
        profileCombo.removeAllItems();
        for (ConnectionProfile profile : settings.getProfiles()) {
            profileCombo.addItem(profile);
        }
        ConnectionProfile active = settings.getActiveProfile();
        if (active != null) {
            profileCombo.setSelectedItem(active);
        }
    }

    private void onProfileSelected() {
        Object selected = profileCombo.getSelectedItem();
        if (selected instanceof ConnectionProfile profile) {
            SqlQuerySettings.getInstance().setActiveProfile(profile.getName());
        }
    }

    private @Nullable ConnectionProfile selectedProfile() {
        Object selected = profileCombo.getSelectedItem();
        return selected instanceof ConnectionProfile profile ? profile : SqlQuerySettings.getInstance().getActiveProfile();
    }

    private void toggleConnection() {
        if (executor.isConnected()) {
            executor.disconnect();
            updateConnectionStateUi();
            setStatus("Disconnected");
            return;
        }

        ConnectionProfile profile = selectedProfile();
        if (profile == null) {
            Messages.showInfoMessage(project, "Create a connection first with the Manage\u2026 button.",
                    "Simple SQL Query");
            return;
        }
        String problem = profile.validationError();
        if (problem != null) {
            Messages.showWarningDialog(project, problem, "Simple SQL Query");
            return;
        }

        setStatus("Connecting to " + profile.describeTarget() + "\u2026");
        executor.connect(project, profile, null, serverInfo -> {
            setStatus("Connected to " + profile.describeTarget());
            refreshDatabases(profile);
            updateConnectionStateUi();
        }, this::showError);
    }

    private void refreshDatabases(@NotNull ConnectionProfile profile) {
        List<String> databases = executor.listDatabases();
        syncingDatabaseCombo = true;
        try {
            databaseCombo.removeAllItems();
            for (String database : databases) {
                databaseCombo.addItem(database);
            }
            databaseCombo.setSelectedItem(profile.getDatabase());
            if (databaseCombo.getSelectedItem() == null && profile.getDatabase() != null) {
                databaseCombo.addItem(profile.getDatabase());
                databaseCombo.setSelectedItem(profile.getDatabase());
            }
        } finally {
            syncingDatabaseCombo = false;
        }
        databaseCombo.setEnabled(!databases.isEmpty());
    }

    private void onDatabaseSelected() {
        if (syncingDatabaseCombo || !executor.isConnected()) {
            return;
        }
        Object selected = databaseCombo.getSelectedItem();
        if (!(selected instanceof String database) || database.isBlank()) {
            return;
        }
        ConnectionProfile current = executor.session().profile();
        if (current != null && database.equals(current.getDatabase())) {
            return;
        }
        setStatus("Switching to " + database + "\u2026");
        executor.switchDatabase(project, database, () -> setStatus("Connected to " + database), message -> {
            showError(message);
            updateConnectionStateUi();
        });
    }

    private void openConnectionDialog() {
        ConnectionProfilesDialog dialog = new ConnectionProfilesDialog(project);
        if (!dialog.showAndGet()) {
            refreshProfileCombo();
            updateConnectionStateUi();
            return;
        }
        refreshProfileCombo();
        ConnectionProfile active = SqlQuerySettings.getInstance().getActiveProfile();
        if (active != null) {
            profileCombo.setSelectedItem(active);
        }
        updateConnectionStateUi();
    }

    private void updateConnectionStateUi() {
        boolean connected = executor.isConnected();
        connectButton.setText(connected ? "Disconnect" : "Connect");
        databaseCombo.setEnabled(connected);
        if (!connected) {
            statusLabel.setText("Not connected");
            statusLabel.setForeground(JBColor.GRAY);
        } else {
            ConnectionProfile profile = executor.session().profile();
            statusLabel.setText("Connected to " + (profile == null ? "database" : profile.describeTarget()));
            statusLabel.setForeground(UIUtil.getLabelForeground());
        }
    }

    // -------------------------------------------------------------- execution

    /** Runs the statement under the caret, or the selection when there is one. */
    void runCurrentStatement() {
        if (!ensureConnected()) {
            return;
        }
        String text = editor.getDocument().getText();
        String selection = editor.getSelectionModel().getSelectedText();
        if (selection != null && !selection.isBlank()) {
            runScript(selection);
            return;
        }

        int caret = editor.getCaretModel().getOffset();
        List<SqlSplitter.Statement> statements = SqlSplitter.split(text);
        if (statements.isEmpty()) {
            setStatus("Nothing to execute");
            return;
        }
        SqlSplitter.Statement target = null;
        for (SqlSplitter.Statement statement : statements) {
            if (caret >= statement.startOffset() && caret <= statement.endOffset()) {
                target = statement;
                break;
            }
        }
        if (target == null) {
            target = caret < statements.get(0).startOffset() ? statements.get(0) : statements.get(statements.size() - 1);
        }
        runScript(target.sql());
    }

    void runScript(@NotNull String script) {
        if (!ensureConnected()) {
            return;
        }
        String trimmed = script.strip();
        if (trimmed.isEmpty()) {
            setStatus("Nothing to execute");
            return;
        }

        SqlQuerySettings settings = SqlQuerySettings.getInstance();
        if (settings.isConfirmDestructiveStatements() && QueryExecutor.isDestructive(trimmed)) {
            int answer = Messages.showYesNoDialog(project,
                    "This statement looks destructive:\n\n" + preview(trimmed) + "\n\nRun it anyway?",
                    "Confirm Destructive Statement", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
        }

        ConnectionProfile profile = executor.session().profile();
        int fetchLimit = profile == null ? 500 : profile.getFetchLimit();
        int timeout = profile == null ? 30 : profile.getStatementTimeoutSeconds();

        setStatus("Executing\u2026");
        resultsTabs.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        executor.executeScript(project, trimmed, fetchLimit, timeout, report -> {
            resultsTabs.setCursor(java.awt.Cursor.getDefaultCursor());
            renderResults(report.results());
            setStatus(report.summarise());
        }, message -> {
            resultsTabs.setCursor(java.awt.Cursor.getDefaultCursor());
            setStatus(shortMessage(message));
            showError(message);
        });
    }

    private static @NotNull String preview(@NotNull String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "\u2026" : flat;
    }

    private static @NotNull String shortMessage(@NotNull String message) {
        int newline = message.indexOf('\n');
        String first = newline < 0 ? message : message.substring(0, newline);
        return first.length() > 160 ? first.substring(0, 160) + "\u2026" : first;
    }

    private void renderResults(@NotNull List<StatementResult> results) {
        resultsTabs.removeAll();
        if (results.isEmpty()) {
            resultsTabs.addTab("No results", emptyPanel("The statement produced no results."));
            return;
        }
        long totalRows = 0;
        int index = 1;
        for (StatementResult result : results) {
            String title = results.size() == 1 ? "Result" : "Result " + index;
            if (result instanceof StatementResult.QueryResult query) {
                totalRows += query.rowCount();
                title += " \u2014 " + query.summary();
                resultsTabs.addTab(title, createQueryPanel(query));
            } else if (result instanceof StatementResult.UpdateResult update) {
                title += " \u2014 " + update.summary();
                resultsTabs.addTab(title, createUpdatePanel(update));
            }
            index++;
        }
        resultsTabs.setSelectedIndex(0);
        resultLabel.setText(totalRows + (totalRows == 1 ? " row" : " rows") + "   ");
    }

    private @NotNull JComponent createQueryPanel(@NotNull StatementResult.QueryResult query) {
        ResultGridModel model = new ResultGridModel(query);
        JBTable table = model.createTable();
        table.getEmptyText().setText("Query returned no rows");

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        if (query.truncated()) {
            JBLabel notice = new JBLabel("Showing the first " + query.rowCount()
                    + " rows (truncated). Raise the row limit on the connection profile to see more.");
            notice.setBorder(JBUI.Borders.empty(2, 6));
            notice.setForeground(JBColor.GRAY);
            panel.add(notice, BorderLayout.SOUTH);
        }
        return panel;
    }

    private @NotNull JComponent createUpdatePanel(@NotNull StatementResult.UpdateResult update) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(monospacedEditorFont());
        area.setBackground(UIUtil.getPanelBackground());
        area.setText(update.summary() + "\nCompleted in " + update.elapsedMillis() + " ms.\n\nSQL:\n" + update.sql());
        JScrollPane scrollPane = new JBScrollPane(area);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    private @NotNull JComponent emptyPanel(@NotNull String text) {
        JPanel panel = new JBPanel<>(new BorderLayout());
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(JBColor.GRAY);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    // ------------------------------------------------------------------ status

    void setStatus(@NotNull String text) {
        statusLabel.setText(text);
        statusLabel.setForeground(executor.isConnected() ? UIUtil.getLabelForeground() : JBColor.GRAY);
    }

    void showError(@NotNull String message) {
        Messages.showErrorDialog(project, message, "Simple SQL Query");
    }

    private boolean ensureConnected() {
        if (executor.isConnected()) {
            return true;
        }
        Messages.showWarningDialog(project, "Not connected to a database. Use Connect first.",
                "Simple SQL Query");
        return false;
    }

    private void clearResults() {
        resultsTabs.removeAll();
        resultLabel.setText(" ");
        setStatus(executor.isConnected() ? "Ready" : "Not connected");
    }

    private void setAutoCommit(boolean autoCommit) {
        if (!executor.isConnected()) {
            SqlQuerySettings.getInstance().setAutoCommit(autoCommit);
            updateConnectionStateUi();
            return;
        }
        executor.setAutoCommit(autoCommit, this::showError);
        SqlQuerySettings.getInstance().setAutoCommit(autoCommit);
        updateConnectionStateUi();
    }

    // ----------------------------------------------------------------- actions

    private final class RunCurrentStatementAction extends AnAction {
        RunCurrentStatementAction() {
            super("Run Statement", "Execute the statement at the caret (Ctrl+Enter)", AllIcons.Actions.Execute);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            runCurrentStatement();
        }
    }

    private final class RunSelectionOrAllAction extends AnAction {
        RunSelectionOrAllAction() {
            super("Run All", "Execute every statement in the editor (Ctrl+Shift+Enter)", AllIcons.Actions.RunAll);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (!ensureConnected()) {
                return;
            }
            runScript(editor.getDocument().getText());
        }
    }

    private final class StopAction extends AnAction {
        StopAction() {
            super("Stop", "Cancel the running statement", AllIcons.Actions.Suspend);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            executor.cancel();
            setStatus("Cancelling\u2026");
        }
    }

    private final class ToggleAutoCommitAction extends AnAction {
        ToggleAutoCommitAction() {
            super("Auto-commit", "Toggle auto-commit for this session", AllIcons.Actions.Commit);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            setAutoCommit(!SqlQuerySettings.getInstance().isAutoCommit());
        }
    }

    private final class CommitAction extends AnAction {
        CommitAction() {
            super("Commit", "Commit the current transaction", AllIcons.Actions.Commit);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            // Only meaningful while auto-commit is off and a session is open.
            e.getPresentation().setEnabled(executor.isConnected()
                    && !SqlQuerySettings.getInstance().isAutoCommit());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (!executor.isConnected()) {
                return;
            }
            executor.commit(SqlQueryPanel.this::showError);
            setStatus("Transaction committed");
        }
    }

    private final class RollbackAction extends AnAction {
        RollbackAction() {
            super("Rollback", "Roll back the current transaction", AllIcons.Actions.Rollback);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(executor.isConnected()
                    && !SqlQuerySettings.getInstance().isAutoCommit());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (!executor.isConnected()) {
                return;
            }
            executor.rollback(SqlQueryPanel.this::showError);
            setStatus("Transaction rolled back");
        }
    }

    private final class ClearResultsAction extends AnAction {
        ClearResultsAction() {
            super("Clear Results", "Clear the results pane", AllIcons.Actions.GC);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            clearResults();
        }
    }
}
