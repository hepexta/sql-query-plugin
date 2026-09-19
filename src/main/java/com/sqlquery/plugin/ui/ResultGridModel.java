/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.table.JBTable;
import com.sqlquery.plugin.db.SqlValueFormatter;
import com.sqlquery.plugin.db.StatementResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only grid for one {@link StatementResult.QueryResult}.
 *
 * <p>Keeps the raw JDBC values so copied text matches the database exactly, while the
 * renderer shows {@code NULL} for SQL nulls and a placeholder for binary data.</p>
 *
 * <p>Cell text is drawn as characters and never as markup. Database values are attacker-influenced
 * in the general case, and a Swing label that parses HTML would not only misrepresent a value like
 * {@code <html>hidden} but would honour an {@code <img src="http://...">} inside it by requesting
 * that URL.</p>
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
     *
     * <p>{@link ColoredTableCellRenderer} is a component that paints appended text fragments. That
     * is the point: it has no HTML mode, so a value cannot change how it is displayed or make the
     * grid fetch anything. A {@link javax.swing.table.DefaultTableCellRenderer} is a {@code JLabel},
     * which parses its text as HTML as soon as it starts with {@code <html>}.</p>
     */
    private static final class ValueRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value,
                                             boolean selected, boolean hasFocus, int row, int column) {
            boolean muted = value == null || value instanceof byte[];
            String text = SqlValueFormatter.display(value);
            append(text, muted
                    ? mutedAttributes(selected)
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            // Tooltips are HTML-capable too, so the same text has to be escaped there.
            setToolTipText(UiText.asHtml(text));
        }

        /**
         * Italic marks a value that is not really text; gray marks it only while the row is not
         * selected. The selected variant has to be {@code REGULAR_ITALIC_ATTRIBUTES}: its colour is
         * null, so it inherits the selection foreground, whereas {@code GRAYED_*} carries its own
         * gray and would stay gray on the selection background.
         */
        private static @NotNull SimpleTextAttributes mutedAttributes(boolean selected) {
            return selected
                    ? SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES
                    : SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES;
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
