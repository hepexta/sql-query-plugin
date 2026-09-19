/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.wm.ToolWindowEP;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Boots the plugin inside a real (headless) IDE instance.
 *
 * <p>Scope note: the headless test IDE registers no tool windows of its own at all
 * ({@code ToolWindowManager.getToolWindowIds()} is empty), so this test does not assert on
 * {@code getToolWindow(...)}. It asserts the things that are genuinely ours â€” that the
 * {@code toolWindow} extension in {@code plugin.xml} resolved against our plugin and points at
 * our factory, and that the factory builds a fully working panel.</p>
 */
public class ToolWindowBootTest extends BasePlatformTestCase {

    private static final String TOOL_WINDOW_ID = "Simple SQL Query";

    /** The extension declared in plugin.xml must be registered and point at our factory. */
    public void testToolWindowExtensionIsRegistered() {
        List<ToolWindowEP> found = new ArrayList<>();
        for (ToolWindowEP ep : ToolWindowEP.EP_NAME.getExtensionList()) {
            if (TOOL_WINDOW_ID.equals(ep.id)) {
                found.add(ep);
            }
        }
        assertEquals("expected exactly one toolWindow extension with id '" + TOOL_WINDOW_ID + "'",
                1, found.size());

        ToolWindowEP ep = found.get(0);
        assertEquals(SqlQueryToolWindowFactory.class.getName(), ep.factoryClass);
        assertEquals("right", ep.anchor);
        assertFalse("tool window should be activated on startup", ep.isDoNotActivateOnStart);
        assertFalse("tool window should not be a secondary stripe button", ep.secondary);
        assertFalse("the content should be closable", ep.canCloseContents);

        // The extension must belong to our plugin and resolve to our factory instance.
        assertEquals("com.sqlquery.simple-sql-query", ep.getPluginDescriptor().getPluginId().getIdString());
        Object factory = ep.getToolWindowFactory(ep.getPluginDescriptor());
        assertTrue("extension did not produce our factory", factory instanceof SqlQueryToolWindowFactory);
    }

    /** The factory builds content, and the panel inside it constructs its whole UI. */
    public void testFactoryBuildsWorkingPanel() {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());
        JComponent component = panel.getComponent();
        assertNotNull(component);
        assertTrue("panel has no preferred width", component.getPreferredSize().width > 0);
        assertTrue("panel has no preferred height", component.getPreferredSize().height > 0);

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        assertNotNull(content.getComponent());
    }

    /** Construction must be repeatable, i.e. free of hidden global state. */
    public void testPanelBuildsRepeatedly() {
        for (int i = 0; i < 3; i++) {
            SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());
            assertNotNull(panel.getComponent());
        }
    }

    /** Dispose must release the editor and disconnect without throwing. */
    public void testDisposeIsClean() {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());
        panel.dispose();
        panel.dispose(); // idempotent
    }

    /**
     * The SQL editor must accept typed input. It was accidentally created as a viewer
     * ({@code createEditor(..., isViewer = true)}), which made the whole editor read-only and
     * made it impossible to type a query.
     */
    public void testSqlEditorIsEditable() throws Exception {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());
        Editor editor = editorOf(panel);

        assertFalse("the SQL editor must not be a read-only viewer", editor.isViewer());
        assertTrue("editor document must be writable", editor.getDocument().isWritable());

        // Editing a real (non-viewer) document needs a write action inside a command, which is
        // exactly what a read-only viewer document would refuse.
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                editor.getDocument().insertString(0, "select 42;\n"));

        assertTrue("typed SQL did not reach the document",
                editor.getDocument().getText().startsWith("select 42;"));
    }

    /**
     * The structure browser must be present and must actually generate a query when a table is
     * activated - the double-click behaviour the whole panel exists for.
     */
    public void testStructureBrowserGeneratesSelectOnTableActivation() {
        List<String> executed = new ArrayList<>();
        DatabaseStructurePanel browser = new DatabaseStructurePanel(executed::add);
        browser.setProvider(new FakeProvider(), "public");
        browser.reload();
        loadAllForTests(browser);

        // The headline behaviour: a table yields "select * from <table> limit 20".
        assertTrue("double-clicking a table must run a statement", executed.isEmpty());
        assertTrue("the table node should be loaded", browser.activateNodeForTests("agent"));
        assertEquals("expected exactly one statement", 1, executed.size());
        assertTrue("generated SQL should be a SELECT capped at 20 rows, was: " + executed,
                executed.get(0).startsWith("select * from public.agent ")
                        && executed.get(0).contains("limit 20"));

        // A view previews the same way.
        executed.clear();
        assertTrue("activating a view must run a statement",
                browser.activateNodeForTests("agent_events_view"));
        assertTrue("a view should also produce a preview, was: " + executed,
                executed.get(0).startsWith("select * from public.agent_events_view "));
    }

    /** Reserving the table name must survive the round trip into generated SQL. */
    public void testStructureBrowserQuotesReservedTableNames() {
        List<String> executed = new ArrayList<>();
        DatabaseStructurePanel browser = new DatabaseStructurePanel(executed::add);
        browser.setProvider(new FakeProvider(), "public");
        browser.reload();
        loadAllForTests(browser);

        assertTrue("the reserved-name table should be loaded",
                browser.activateNodeForTests("user"));
        assertEquals("select * from public.\"user\" limit 20;", executed.get(0));
    }

    /** The structure browser must sit in the tool window as a left-hand panel. */
    public void testStructureBrowserIsPartOfTheLayout() {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());
        DatabaseStructurePanel browser = panel.getStructurePanelForTests();
        assertNotNull("the structure browser is not part of the panel", browser);
        assertTrue("the structure browser should be laid out inside the tool window",
                SwingUtilities.isDescendingFrom(browser, panel.getContent()));
    }

    /** Minimal in-memory structure provider: one schema, one table, one view. */
    private static final class FakeProvider implements DatabaseStructurePanel.StructureProvider {
        @Override
        public @NotNull List<String> schemaNames() {
            return List.of("public");
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Relation> relations(@NotNull String schema) {
            return List.of(
                    new com.sqlquery.plugin.db.DbStructureReader.Relation("public", "agent_events_view", "VIEW", -1, null),
                    new com.sqlquery.plugin.db.DbStructureReader.Relation("public", "user", "TABLE", 3, null),
                    new com.sqlquery.plugin.db.DbStructureReader.Relation("public", "agent", "TABLE", 42, null));
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Column> columns(@NotNull String schema,
                                                                                     @NotNull String relation) {
            return List.of(new com.sqlquery.plugin.db.DbStructureReader.Column("id", "int8", false, null, null));
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Index> indexes(@NotNull String schema,
                                                                                    @NotNull String relation) {
            return List.of();
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Routine> routines(@NotNull String schema,
                                                                                       boolean procedures) {
            return List.of();
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Trigger> triggers(@NotNull String schema) {
            return List.of();
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Sequence> sequences(@NotNull String schema) {
            return List.of();
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.TypeInfo> types(@NotNull String schema) {
            return List.of();
        }

        @Override
        public @NotNull List<com.sqlquery.plugin.db.DbStructureReader.Extension> extensions() {
            return List.of();
        }
    }

    /**
     * Loading runs on a pooled thread in the IDE and posts back to the EDT; tests already run on
     * the EDT, where the panel deliberately loads inline. Opening the folders therefore loads
     * their children synchronously.
     */
    private static void loadAllForTests(@NotNull DatabaseStructurePanel browser) {
        assertTrue("structure browser never loaded the schema list",
                browser.getRootForTests().getChildCount() > 0);
        javax.swing.tree.DefaultMutableTreeNode schema =
                (javax.swing.tree.DefaultMutableTreeNode) browser.getRootForTests().getChildAt(0);
        browser.loadChildrenForTests(schema);

        for (int i = 0; i < schema.getChildCount(); i++) {
            javax.swing.tree.DefaultMutableTreeNode category =
                    (javax.swing.tree.DefaultMutableTreeNode) schema.getChildAt(i);
            browser.loadChildrenForTests(category);
        }
    }

    /** The Run toolbar must be populated with the execution actions and wired into the panel. */
    public void testRunToolbarIsWiredIntoThePanel() {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());

        DefaultActionGroup group = panel.getEditorActionGroupForTests();
        assertNotNull("the Run action group is missing", group);

        List<String> actionNames = new ArrayList<>();
        for (AnAction action : group.getChildActionsOrStubs()) {
            if (action instanceof Separator) {
                continue;
            }
            actionNames.add(action.getTemplatePresentation().getText());
        }
        assertTrue("expected the Run action in the toolbar, got " + actionNames,
                actionNames.contains("Run Statement"));
        assertTrue("expected the Run All action in the toolbar, got " + actionNames,
                actionNames.contains("Run All"));
        assertTrue("expected the Stop action in the toolbar, got " + actionNames,
                actionNames.contains("Stop"));

        JComponent wrapper = panel.getEditorWrapperForTests();
        assertNotNull("the editor container is missing", wrapper);
        assertEquals("the Run toolbar must be attached at the top of the editor container",
                BorderLayout.NORTH, layoutOf(wrapper).getConstraints(panel.getEditorToolbarComponentForTests()));

        JComponent editorComponent = editorOf(panel).getContentComponent();
        assertTrue("the editor must live inside the editor container",
                SwingUtilities.isDescendingFrom(editorComponent, wrapper));
    }

    /**
     * The connection bar belongs on the top edge, spanning the full width. With the container's
     * orientation flag the wrong way round it was placed on the WEST edge, which pushed the
     * whole editor to the right - the layout problem this test guards against.
     */
    public void testConnectionBarSitsOnTopEdge() {
        SqlQueryPanel panel = new SqlQueryPanel(getProject(), getTestRootDisposable());

        JComponent toolbar = panel.getToolbar();
        assertNotNull("the connection bar is missing", toolbar);
        assertEquals("the connection bar must sit on the NORTH edge, not the WEST edge",
                BorderLayout.NORTH, layoutOf(panel).getConstraints(toolbar));
    }

    private static @NotNull Editor editorOf(@NotNull SqlQueryPanel panel) {
        Editor editor = panel.getEditorForTests();
        assertNotNull("panel has no editor", editor);
        return editor;
    }

    private static @NotNull BorderLayout layoutOf(@NotNull java.awt.Container container) {
        java.awt.LayoutManager layout = container.getLayout();
        assertTrue("panel should use BorderLayout, was " + layout, layout instanceof BorderLayout);
        return (BorderLayout) layout;
    }

    /** The descriptor depends on this bundled plugin, so it must really be present. */
    public void testDeclaredPluginDependenciesAreAvailable() {
        assertNotNull("com.intellij.database plugin is not installed in the test IDE",
                PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database")));
    }

    /** Our own descriptor must be loaded from the sandbox, not silently missing. */
    public void testOurPluginIsLoaded() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(
                PluginId.getId("com.sqlquery.simple-sql-query"));
        assertNotNull("the plugin under test is not loaded in the test IDE", descriptor);
        assertNotNull("plugin has no class loader, so its classes cannot be resolved",
                descriptor.getClassLoader());
    }

    /** The settings service declared in plugin.xml must be resolvable. */
    public void testSettingsServiceResolves() {
        com.sqlquery.plugin.settings.SqlQuerySettings settings =
                com.sqlquery.plugin.settings.SqlQuerySettings.getInstance();
        assertNotNull("SqlQuerySettings service did not resolve", settings);
        assertNotNull("settings returned no state", settings.getState());
        assertFalse("expected at least one seeded connection profile", settings.getProfiles().isEmpty());
    }
}
