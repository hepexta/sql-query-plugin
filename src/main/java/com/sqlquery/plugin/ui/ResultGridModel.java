/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.ui.table.JBTable;
import com.sqlquery.plugin.db.SqlValueFormatter;
import com.sqlquery.plugin.db.StatementResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only grid for one {@link StatementResult.QueryResult}.
 *
 * <p>Keeps the raw JDBC values so copied text matches the database exactly, while the
 * renderer shows {@code NULL} for SQL nulls and a placeholder for binary data.</p>
 */
public final class ResultGridModel extends AbstractTableModel {

    private final transient StatementResult.QueryResult result;

    public ResultGridModel(@NotNull StatementResult.QueryResult result) {
        this.result = result;
    }

    @Override
    public int getRowCount() {
        return result.rows().size();
    }

    @Override
    public int getColumnCount() {
        return result.columns().size();
    }

    @Override
    public @NotNull String getColumnName(int column) {
        return result.columns().get(column).header();
    }

    @Override
    public @NotNull Class<?> getColumnClass(int columnIndex) {
        return Object.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public @Nullable Object getValueAt(int rowIndex, int columnIndex) {
        Object[] row = result.rows().get(rowIndex);
        return columnIndex < row.length ? row[columnIndex] : null;
    }

    /** The SQL text of the statement that produced this grid. */
    public @NotNull String sql() {
        return result.sql();
    }

    public @NotNull StatementResult.QueryResult queryResult() {
        return result;
    }

    /** Builds the configured table for this result. */
    public @NotNull JBTable createTable() {
        JBTable table = new JBTable(this);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSorter(new TableRowSorter<>(this));
        table.setDefaultRenderer(Object.class, new ValueRenderer());
        table.setCellSelectionEnabled(true);
        table.setShowGrid(true);
        ResultGridSupport.installColumnSizing(table);
        ResultGridSupport.installContextMenu(table);
        table.getEmptyText().setText("Query returned no rows");
        return table;
    }

    /**
     * Cells are rendered through {@link SqlValueFormatter}; SQL nulls and binary values get a
     * muted italic look so they are distinguishable from the strings {@code NULL} and empty text.
     */
    private static final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public @NotNull Component getTableCellRendererComponent(@NotNull JTable table, @Nullable Object value,
                                                              boolean isSelected, boolean hasFocus,
                                                              int row, int column) {
            Object raw = value;
            String text = SqlValueFormatter.display(raw);
            Component component = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);

            boolean isNull = raw == null;
            boolean isBinary = raw instanceof byte[];
            if (isNull || isBinary) {
                component.setFont(component.getFont().deriveFont(Font.ITALIC));
                if (!isSelected) {
                    component.setForeground(com.intellij.ui.JBColor.GRAY);
                }
            } else if (!isSelected) {
                component.setForeground(table.getForeground());
            }
            setToolTipText(text);
            return component;
        }
    }

    /** Plain text rendering of the whole grid, used by "Copy All". */
    public @NotNull String toTsv() {
        StringBuilder sb = new StringBuilder();
        List<String> headers = new ArrayList<>(getColumnCount());
        for (int c = 0; c < getColumnCount(); c++) {
            headers.add(getColumnName(c));
        }
        sb.append(String.join("\t", headers)).append('\n');
        for (Object[] row : result.rows()) {
            for (int c = 0; c < row.length; c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                sb.append(SqlValueFormatter.clipboard(row[c]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
