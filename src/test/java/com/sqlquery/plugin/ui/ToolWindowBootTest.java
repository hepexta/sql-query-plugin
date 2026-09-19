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
