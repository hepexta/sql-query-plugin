/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.sqlquery.plugin.db.DbStructureReader;
import com.sqlquery.plugin.db.SqlIdentifiers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Database structure browser: a tree of schemas and the objects inside them.
 *
 * <p>Children load lazily when a node is expanded, on a pooled thread, so a database with
 * hundreds of tables never blocks the EDT. Double-clicking a table or view runs
 * {@code select * from <relation> limit 20} through the owning panel; other object kinds
 * generate a useful starting statement instead.</p>
 */
public final class DatabaseStructurePanel extends JBPanel<DatabaseStructurePanel> {

    /** What a tree node represents; decides both the icon and the double-click behaviour. */
    private enum NodeKind {
        SCHEMA, CATEGORY, RELATION, COLUMN, INDEX, ROUTINE, TRIGGER, SEQUENCE, TYPE, EXTENSION, MESSAGE
    }

    /** Immutable payload attached to every tree node. */
    private static final class Node {
        final NodeKind kind;
        final String label;
        final String detail;
        final String schema;
        final String name;
        final boolean tableLike;
        final String typeLabel;

        Node(@NotNull NodeKind kind, @NotNull String label, @Nullable String detail,
             @Nullable String schema, @Nullable String name,
             boolean tableLike, @Nullable String typeLabel) {
            this.kind = kind;
            this.label = label;
            this.detail = detail;
            this.schema = schema;
            this.name = name;
            this.tableLike = tableLike;
            this.typeLabel = typeLabel;
        }

        static @NotNull Node message(@NotNull String text) {
            return new Node(NodeKind.MESSAGE, text, null, null, null, false, null);
        }
    }

    /**
     * Supplies structure data. Implemented by the tool window panel, which owns the JDBC
     * session and runs these on a background thread.
     */
    public interface StructureProvider {
        @NotNull List<String> schemaNames() throws SQLException;

        @NotNull List<DbStructureReader.Relation> relations(@NotNull String schema) throws SQLException;

        @NotNull List<DbStructureReader.Column> columns(@NotNull String schema, @NotNull String relation) throws SQLException;

        @NotNull List<DbStructureReader.Index> indexes(@NotNull String schema, @NotNull String relation) throws SQLException;

        @NotNull List<DbStructureReader.Routine> routines(@NotNull String schema, boolean procedures) throws SQLException;

        @NotNull List<DbStructureReader.Trigger> triggers(@NotNull String schema) throws SQLException;

        @NotNull List<DbStructureReader.Sequence> sequences(@NotNull String schema) throws SQLException;

        @NotNull List<DbStructureReader.TypeInfo> types(@NotNull String schema) throws SQLException;

        @NotNull List<DbStructureReader.Extension> extensions() throws SQLException;
    }

    private static final List<String> CATEGORIES = List.of(
            "Tables", "Views", "Materialized Views", "Functions",
            "Procedures", "Triggers", "Sequences", "Types", "Extensions");

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(Node.message("Database"));
    private final DefaultTreeModel model = new DefaultTreeModel(rootNode);
    private final Tree tree = new Tree(model);
    private final JBTextField filterField = new JBTextField();
    private final JBLabel statusLabel = new JBLabel("Not connected");

    private final Consumer<String> onRunSql;

    private @Nullable StructureProvider provider;
    private String preferredSchema = "public";

    /**
     * @param onRunSql invoked with a statement the user asked to run by double-clicking
     */
    public DatabaseStructurePanel(@NotNull Consumer<String> onRunSql) {
        super(new BorderLayout());
        this.onRunSql = onRunSql;

        configureTree();
        add(createTopBar(), BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(tree);
        scrollPane.setBorder(JBUI.Borders.empty());
        add(scrollPane, BorderLayout.CENTER);

        statusLabel.setBorder(JBUI.Borders.empty(3, 6));
        statusLabel.setForeground(UIUtil.getContextHelpForeground());
        add(statusLabel, BorderLayout.SOUTH);

        showDisconnected();
    }

    // -------------------------------------------------------------------- setup

    private void configureTree() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new StructureCellRenderer());
        tree.getEmptyText().setText("Not connected");

        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode node) {
                    loadChildren(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // nothing to release
            }
        });

        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null
                        && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                        && node.getUserObject() instanceof Node payload) {
                    return activate(payload);
                }
                return false;
            }
        }.installOn(tree);
    }

    private @NotNull JComponent createTopBar() {
        JPanel bar = new JBPanel<>(new BorderLayout());
        bar.setBorder(JBUI.Borders.empty(3, 4));

        filterField.getEmptyText().setText("Filter objects\u2026");
        filterField.setEnabled(false);
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RefreshAction());
        group.add(new CollapseAction());
        group.add(new PreviewAction());
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("SimpleSqlQuery.Structure", group, true);
        toolbar.setTargetComponent(this);

        bar.add(filterField, BorderLayout.CENTER);
        bar.add(toolbar.getComponent(), BorderLayout.EAST);
        return bar;
    }

    /** Installs the data source. Pass {@code null} on disconnect. */
    public void setProvider(@Nullable StructureProvider provider, @Nullable String defaultSchema) {
        this.provider = provider;
        if (defaultSchema != null && !defaultSchema.isBlank()) {
            this.preferredSchema = defaultSchema;
        }
        filterField.setEnabled(provider != null);
        if (provider == null) {
            showDisconnected();
        }
    }

    private void showDisconnected() {
        rootNode.removeAllChildren();
        model.reload();
        tree.getEmptyText().setText("Not connected");
        filterField.setText("");
        statusLabel.setText("Not connected");
    }

    // ------------------------------------------------------------------ loading

    /** Reloads the schema list. Call from the EDT. */
    public void reload() {
        StructureProvider active = provider;
        if (active == null) {
            showDisconnected();
            return;
        }
        statusLabel.setText("Loading structure\u2026");
        tree.getEmptyText().setText("Loading\u2026");

        background(active::schemaNames, schemas -> {
            rootNode.removeAllChildren();
            for (String schema : schemas) {
                DefaultMutableTreeNode schemaNode =
                        new DefaultMutableTreeNode(new Node(NodeKind.SCHEMA, schema, null, schema, schema, false, null));
                schemaNode.add(loadingNode());
                rootNode.add(schemaNode);
            }
            model.reload();
            tree.getEmptyText().setText("No schemas");
            statusLabel.setText(schemas.size() + (schemas.size() == 1 ? " schema" : " schemas"));
            expandPreferredSchema();
        }, error -> {
            rootNode.removeAllChildren();
            model.reload();
            tree.getEmptyText().setText("Could not read the structure");
            statusLabel.setText("Structure unavailable: " + error);
        });
    }

    private void expandPreferredSchema() {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (child.getUserObject() instanceof Node node && preferredSchema.equals(node.name)) {
                TreePath path = new TreePath(child.getPath());
                tree.expandPath(path);
                tree.setSelectionPath(path);
                SwingUtilities.invokeLater(() -> expandChild(child, "Tables"));
                return;
            }
        }
    }

    private void expandChild(@NotNull DefaultMutableTreeNode parent, @NotNull String label) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof Node node && label.equals(node.label)) {
                tree.expandPath(new TreePath(child.getPath()));
                return;
            }
        }
    }

    private static @NotNull DefaultMutableTreeNode loadingNode() {
        return new DefaultMutableTreeNode(Node.message("loading\u2026"));
    }

    private static boolean isLoadingPlaceholder(@NotNull DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() instanceof Node child
                && child.kind == NodeKind.MESSAGE
                && "loading\u2026".equals(child.label);
    }

    private void loadChildren(@NotNull DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Node payload) || !isLoadingPlaceholder(node)) {
            return;
        }
        switch (payload.kind) {
            case SCHEMA -> loadCategories(node, payload);
            case CATEGORY -> loadCategory(node, payload);
            case RELATION -> loadRelationDetail(node, payload);
            default -> {
                // leaves have nothing to load
            }
        }
    }

    private void loadCategories(@NotNull DefaultMutableTreeNode node, @NotNull Node schema) {
        node.removeAllChildren();
        for (String category : CATEGORIES) {
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                    new Node(NodeKind.CATEGORY, category, null, schema.name, category, false, null));
            categoryNode.add(loadingNode());
            node.add(categoryNode);
        }
        model.reload(node);
    }

    private void loadCategory(@NotNull DefaultMutableTreeNode node, @NotNull Node category) {
        StructureProvider active = provider;
        if (active == null) {
            return;
        }
        String schema = category.schema;
        String categoryName = category.name;

        background(() -> {
            List<Node> children = new ArrayList<>();
            switch (categoryName) {
                case "Tables", "Views", "Materialized Views" -> {
                    for (DbStructureReader.Relation relation : active.relations(schema)) {
                        if (!belongsTo(relation.kind(), categoryName)) {
                            continue;
                        }
                        children.add(new Node(NodeKind.RELATION, relation.name(),
                                describe(relation), schema, relation.name(),
                                relation.isTableLike(), relation.kind()));
                    }
                }
                case "Functions", "Procedures" -> {
                    boolean procedures = "Procedures".equals(categoryName);
                    for (DbStructureReader.Routine routine : active.routines(schema, procedures)) {
                        children.add(new Node(NodeKind.ROUTINE, routine.signature(),
                                routine.returnType(), schema, routine.name(), false, routine.kind()));
                    }
                }
                case "Triggers" -> {
                    for (DbStructureReader.Trigger trigger : active.triggers(schema)) {
                        children.add(new Node(NodeKind.TRIGGER, trigger.name(),
                                trigger.relation() + " \u00b7 " + trigger.description(),
                                schema, trigger.relation(), false, "TRIGGER"));
                    }
                }
                case "Sequences" -> {
                    for (DbStructureReader.Sequence sequence : active.sequences(schema)) {
                        children.add(new Node(NodeKind.SEQUENCE, sequence.name(),
                                sequence.dataType(), schema, sequence.name(), false, "SEQUENCE"));
                    }
                }
                case "Types" -> {
                    for (DbStructureReader.TypeInfo type : active.types(schema)) {
                        children.add(new Node(NodeKind.TYPE, type.name(), type.kind(),
                                schema, type.name(), false, type.kind()));
                    }
                }
                case "Extensions" -> {
                    for (DbStructureReader.Extension extension : active.extensions()) {
                        children.add(new Node(NodeKind.EXTENSION, extension.name(),
                                "v" + extension.version(), extension.schema(), extension.name(),
                                false, "EXTENSION"));
                    }
                }
                default -> {
                    // no other categories exist
                }
            }
            return children;
        }, children -> {
            node.removeAllChildren();
            if (children.isEmpty()) {
                node.add(new DefaultMutableTreeNode(Node.message("empty")));
            } else {
                for (Node child : children) {
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                    if (child.kind == NodeKind.RELATION) {
                        childNode.add(loadingNode());
                    }
                    node.add(childNode);
                }
            }
            model.reload(node);
        }, error -> replaceWithMessage(node, "error: " + error));
    }

    /** Which relation kinds each folder holds. */
    private static boolean belongsTo(@NotNull String kind, @NotNull String category) {
        return switch (category) {
            case "Tables" -> "TABLE".equals(kind) || "PARTITIONED TABLE".equals(kind)
                    || "FOREIGN TABLE".equals(kind);
            case "Views" -> "VIEW".equals(kind);
            case "Materialized Views" -> "MATERIALIZED VIEW".equals(kind);
            default -> false;
        };
    }

    private void loadRelationDetail(@NotNull DefaultMutableTreeNode node, @NotNull Node relation) {
        StructureProvider active = provider;
        if (active == null) {
            return;
        }
        String schema = relation.schema;
        String name = relation.name;

        background(() -> {
            List<Node> children = new ArrayList<>();
            for (DbStructureReader.Column column : active.columns(schema, name)) {
                StringBuilder detail = new StringBuilder(column.dataType());
                if (!column.nullable()) {
                    detail.append(" NOT NULL");
                }
                if (column.defaultValue() != null) {
                    detail.append(" default ").append(column.defaultValue());
                }
                children.add(new Node(NodeKind.COLUMN, column.name(), detail.toString(),
                        schema, name, false, column.dataType()));
            }
            for (DbStructureReader.Index index : active.indexes(schema, name)) {
                String kind = index.primary() ? "PRIMARY KEY" : index.unique() ? "UNIQUE" : "INDEX";
                children.add(new Node(NodeKind.INDEX, index.name(),
                        kind + " \u00b7 " + index.definition(), schema, name, false, kind));
            }
            return children;
        }, children -> {
            node.removeAllChildren();
            if (children.isEmpty()) {
                node.add(new DefaultMutableTreeNode(Node.message("no columns")));
            } else {
                for (Node child : children) {
                    node.add(new DefaultMutableTreeNode(child));
                }
            }
            model.reload(node);
        }, error -> replaceWithMessage(node, "error: " + error));
    }

    private void replaceWithMessage(@NotNull DefaultMutableTreeNode node, @NotNull String text) {
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(Node.message(text)));
        model.reload(node);
    }

    private static @NotNull String describe(@NotNull DbStructureReader.Relation relation) {
        if (relation.isTableLike() && relation.estimatedRows() >= 0) {
            return relation.kind() + " \u00b7 ~" + relation.estimatedRows() + " rows";
        }
        return relation.kind();
    }

    // ------------------------------------------------------------ double click

    /**
     * The core interaction: a table or view previews its rows, everything else opens a
     * skeleton statement that is useful rather than an error.
     */
    private boolean activate(@NotNull Node node) {
        switch (node.kind) {
            case RELATION -> {
                onRunSql.accept(SqlIdentifiers.selectPreview(node.schema, node.name));
                return true;
            }
            case COLUMN -> {
                onRunSql.accept(SqlIdentifiers.selectPreview(node.schema, node.name));
                return true;
            }
            case ROUTINE -> {
                onRunSql.accept(SqlIdentifiers.callRoutine(node.schema, node.name));
                return true;
            }
            case SEQUENCE -> {
                onRunSql.accept(SqlIdentifiers.sequenceValue(node.schema, node.name));
                return true;
            }
            case TRIGGER -> {
                onRunSql.accept(SqlIdentifiers.triggerSkeleton(node.schema, node.name));
                return true;
            }
            case SCHEMA -> {
                onRunSql.accept(SqlIdentifiers.schemaOverview(node.schema));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ filter

    private void applyFilter() {
        String filter = filterField.getText().trim().toLowerCase();
        if (!filter.isEmpty()) {
            // Filtering only sees loaded nodes, so make sure the common folders are open.
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                DefaultMutableTreeNode schema = (DefaultMutableTreeNode) rootNode.getChildAt(i);
                tree.expandPath(new TreePath(schema.getPath()));
                for (int j = 0; j < schema.getChildCount(); j++) {
                    Object child = ((DefaultMutableTreeNode) schema.getChildAt(j)).getUserObject();
                    if (child instanceof Node node && CATEGORIES.contains(node.label)) {
                        tree.expandPath(new TreePath(((DefaultMutableTreeNode) schema.getChildAt(j)).getPath()));
                    }
                }
            }
            expandMatching(rootNode, filter);
        }
    }

    private void expandMatching(@NotNull DefaultMutableTreeNode node, @NotNull String filter) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof Node payload && payload.label.toLowerCase().contains(filter)) {
                tree.expandPath(new TreePath(child.getPath()));
                tree.makeVisible(new TreePath(child.getPath()));
            }
            expandMatching(child, filter);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private interface Work<T> {
        @NotNull T run() throws Exception;
    }

    /**
     * Runs {@code work} and delivers the outcome back on the EDT.
     *
     * <p>Normally the work is handed to a pooled thread so the UI stays responsive while a
     * large schema is read. When the calling thread <em>is</em> the EDT - which happens in the
     * headless tests - the work runs inline instead: posting to the EDT and waiting would
     * deadlock, and there is nothing to keep responsive.</p>
     */
    private <T> void background(@NotNull Work<T> work,
                                @NotNull Consumer<T> onSuccess,
                                @NotNull Consumer<String> onError) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDispatchThread()) {
            try {
                onSuccess.accept(work.run());
            } catch (Exception e) {
                onError.accept(shorten(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            return;
        }
        application.executeOnPooledThread(() -> {
            T result;
            try {
                result = work.run();
            } catch (Exception e) {
                String message = shorten(e.getMessage() == null ? e.toString() : e.getMessage());
                application.invokeLater(() -> onError.accept(message));
                return;
            }
            T value = result;
            application.invokeLater(() -> onSuccess.accept(value));
        });
    }

    private static @NotNull String shorten(@NotNull String message) {
        int newline = message.indexOf('\n');
        String first = newline < 0 ? message : message.substring(0, newline);
        return first.length() > 120 ? first.substring(0, 120) + "\u2026" : first;
    }

    // ---------------------------------------------------- test-visible accessors

    /** Exposed for tests. */
    public @NotNull Tree getTreeForTests() {
        return tree;
    }

    /** Exposed for tests. */
    public @NotNull DefaultMutableTreeNode getRootForTests() {
        return rootNode;
    }

    /** Loads the children of a node synchronously; test helper. */
    public void loadChildrenForTests(@NotNull DefaultMutableTreeNode node) {
        loadChildren(node);
    }

    /**
     * Runs the double-click behaviour for the first loaded node whose label matches, exactly as
     * a user double-clicking that row would. Returns false when no such node is loaded.
     *
     * <p>Used by the UI tests to prove that double-clicking a table generates a query, without
     * needing to synthesise mouse events.</p>
     */
    public boolean activateNodeForTests(@NotNull String label) {
        DefaultMutableTreeNode node = findLoadedNode(rootNode, label);
        if (node == null || !(node.getUserObject() instanceof Node payload)) {
            return false;
        }
        return activate(payload);
    }

    private static @Nullable DefaultMutableTreeNode findLoadedNode(@NotNull DefaultMutableTreeNode node,
                                                                  @NotNull String label) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof Node payload && label.equals(payload.label)) {
                return child;
            }
            DefaultMutableTreeNode found = findLoadedNode(child, label);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- rendering

    /**
     * Renders one tree row: a kind icon, the object name, and a greyed detail suffix.
     *
     * <p>Extends {@link ColoredTreeCellRenderer} rather than {@code Tree.SmartTreeCellRenderer},
     * which does not exist in the platform API.</p>
     */
    private static final class StructureCellRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull javax.swing.JTree tree,
                                          Object value,
                                          boolean selected,
                                          boolean expanded,
                                          boolean leaf,
                                          int row,
                                          boolean hasFocus) {
            if (value instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof Node payload) {
                setIcon(iconFor(payload));
                append(payload.label, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (payload.detail != null && !payload.detail.isBlank()) {
                    append("   " + payload.detail, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            } else {
                append(String.valueOf(value), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }

        private static @NotNull Icon iconFor(@NotNull Node node) {
            return switch (node.kind) {
                case SCHEMA -> AllIcons.Nodes.DataSchema;
                case CATEGORY -> AllIcons.Nodes.Folder;
                case RELATION -> node.typeLabel != null && node.typeLabel.contains("VIEW")
                        ? AllIcons.Nodes.DataColumn
                        : AllIcons.Nodes.DataTables;
                case COLUMN -> AllIcons.Nodes.DataColumn;
                case INDEX -> AllIcons.Nodes.FieldPK;
                case ROUTINE -> node.typeLabel != null && node.typeLabel.startsWith("PROCEDURE")
                        ? AllIcons.Nodes.Method
                        : AllIcons.Nodes.Function;
                case TRIGGER -> AllIcons.Nodes.RunnableMark;
                case SEQUENCE -> AllIcons.Nodes.Record;
                case TYPE -> AllIcons.Nodes.Type;
                case EXTENSION -> AllIcons.Nodes.Plugin;
                case MESSAGE -> AllIcons.General.Information;
            };
        }
    }

    // ------------------------------------------------------------------ actions

    private final class RefreshAction extends AnAction {
        RefreshAction() {
            super("Refresh", "Reload the database structure", AllIcons.Actions.Refresh);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(provider != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            reload();
        }
    }

    private final class CollapseAction extends AnAction {
        CollapseAction() {
            super("Collapse All", "Collapse the whole tree", AllIcons.Actions.Collapseall);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            for (int i = tree.getRowCount() - 1; i >= 0; i--) {
                tree.collapseRow(i);
            }
        }
    }

    private final class PreviewAction extends AnAction {
        PreviewAction() {
            super("Preview Selected", "Run a SELECT for the selected object", AllIcons.Actions.Execute);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            TreePath path = tree.getSelectionPath();
            if (path != null
                    && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof Node payload) {
                activate(payload);
            }
        }
    }
}
