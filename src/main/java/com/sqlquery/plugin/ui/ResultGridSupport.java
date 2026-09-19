/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.ide.CopyProvider;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.table.JBTable;
import com.sqlquery.plugin.db.SqlValueFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/** Clipboard actions, Ctrl+C handling and column sizing shared by every results grid. */
public final class ResultGridSupport {

    private ResultGridSupport() {
    }

    /** Sizes columns to their header and a sample of the rows, capped so wide text does not take over. */
    public static void installColumnSizing(@NotNull JBTable table) {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        FontMetrics metrics = table.getFontMetrics(table.getFont());
        int sampleRows = Math.min(table.getRowCount(), 200);

        for (int column = 0; column < table.getColumnCount(); column++) {
            TableColumn tableColumn = table.getColumnModel().getColumn(column);
            int width = metrics.stringWidth(String.valueOf(table.getColumnName(column))) + 24;
            for (int row = 0; row < sampleRows; row++) {
                Object value = table.getValueAt(row, column);
                String text = SqlValueFormatter.display(value);
                if (text.length() > 120) {
                    text = text.substring(0, 120);
                }
                width = Math.max(width, metrics.stringWidth(text) + 16);
            }
            tableColumn.setPreferredWidth(Math.min(Math.max(width, 44), 420));
        }
    }

    /** Right-click menu with the standard copy actions. */
    public static void installContextMenu(@NotNull JBTable table) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new CopyCellAction(table));
        group.add(new CopyRowAction(table));
        group.add(new CopyAllAction(table));
        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("SimpleSqlQuery.Results", group);
        table.setComponentPopupMenu(popup.getComponent());
    }

    /** Ctrl+C copies the selected cells as tab separated text. */
    public static void copySelection(@NotNull JBTable table) {
        int[] rows = table.getSelectedRows();
        int[] columns = table.getSelectedColumns();
        if (rows.length == 0 || columns.length == 0) {
            return;
        }
        if (rows.length == 1 && columns.length == 1) {
            Object value = table.getValueAt(rows[0], columns[0]);
            CopyPasteManager.getInstance().setContents(new StringSelection(SqlValueFormatter.clipboard(value)));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                sb.append(SqlValueFormatter.clipboard(table.getValueAt(r, columns[i])));
            }
            sb.append('\n');
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
    }

    static @NotNull String copyAll(@NotNull JBTable table) {
        if (table.getModel() instanceof ResultGridModel model) {
            return model.toTsv();
        }
        return "";
    }

    static @NotNull String copyRow(@NotNull JBTable table, int viewRow) {
        StringBuilder sb = new StringBuilder();
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (column > 0) {
                sb.append('\t');
            }
            sb.append(SqlValueFormatter.clipboard(table.getValueAt(viewRow, column)));
        }
        return sb.toString();
    }

    /** Base class that keeps the actions enabled only when a grid with selection is present. */
    abstract static class GridAction extends AnAction {
        final JBTable table;

        GridAction(@NotNull JBTable table, @NotNull String text) {
            super(text);
            this.table = table;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(table.isShowing() && table.getRowCount() > 0);
        }
    }

    static final class CopyCellAction extends GridAction {
        CopyCellAction(@NotNull JBTable table) {
            super(table, "Copy Cell");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            copySelection(table);
        }
    }

    static final class CopyRowAction extends GridAction {
        CopyRowAction(@NotNull JBTable table) {
            super(table, "Copy Row");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            int row = table.getSelectedRow();
            if (row >= 0) {
                CopyPasteManager.getInstance().setContents(new StringSelection(copyRow(table, row)));
            }
        }
    }

    static final class CopyAllAction extends GridAction {
        CopyAllAction(@NotNull JBTable table) {
            super(table, "Copy All (with headers)");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            CopyPasteManager.getInstance().setContents(new StringSelection(copyAll(table)));
        }
    }
}
