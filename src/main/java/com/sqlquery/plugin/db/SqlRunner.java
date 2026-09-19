/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes SQL statements over an already open JDBC connection.
 *
 * <p>Depends on nothing but {@code java.sql}, which keeps it verifiable by a plain
 * integration test against a real PostgreSQL server.</p>
 */
public final class SqlRunner {

    private SqlRunner() {
    }

    /**
     * Runs one statement.
     *
     * @param maxRows    row cap for result sets, {@code <= 0} means unlimited
     * @param timeoutSec {@code Statement} timeout in seconds, {@code <= 0} means no timeout
     */
    public static @NotNull StatementResult execute(@NotNull Connection connection,
                                                   @NotNull String sql,
                                                   int maxRows,
                                                   int timeoutSec) throws SQLException {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            if (timeoutSec > 0) {
                statement.setQueryTimeout(timeoutSec);
            }
            if (maxRows > 0) {
                // Ask for one extra row so we can tell "exactly maxRows" from "truncated".
                statement.setMaxRows(maxRows + 1);
            }

            boolean hasResultSet = statement.execute(sql);
            long elapsed = elapsedMillis(started);
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    return readResultSet(sql, rs, maxRows, elapsed);
                }
            }
            long updated = -1;
            try {
                updated = statement.getLargeUpdateCount();
            } catch (SQLException | UnsupportedOperationException ignored) {
                // fall back below
            }
            return new StatementResult.UpdateResult(sql, updated, elapsed);
        }
    }

    private static @NotNull StatementResult.QueryResult readResultSet(@NotNull String sql,
                                                                     @NotNull ResultSet rs,
                                                                     int maxRows,
                                                                     long elapsed) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<StatementResult.Column> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new StatementResult.Column(label(meta, i), columnType(meta, i)));
        }

        List<Object[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Object[] row = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                row[i - 1] = materialize(rs.getObject(i));
            }
            rows.add(row);
        }
        return new StatementResult.QueryResult(sql, columns, rows, truncated, elapsed);
    }

    /** Driver reported type name, e.g. {@code int4} or {@code timestamptz}. */
    private static @NotNull String columnType(@NotNull ResultSetMetaData meta, int index) {
        int sqlType;
        try {
            sqlType = meta.getColumnType(index);
        } catch (SQLException ignored) {
            sqlType = java.sql.Types.OTHER;
        }
        String driverName = null;
        try {
            driverName = meta.getColumnTypeName(index);
        } catch (SQLException ignored) {
            // leave null and fall back to the JDBC type
        }
        return StatementResult.typeNameOf(sqlType, driverName);
    }

    private static @NotNull String label(@NotNull ResultSetMetaData meta, int index) {
        String label = safeString(() -> meta.getColumnLabel(index));
        if (label == null || label.isBlank()) {
            label = safeString(() -> meta.getColumnName(index));
        }
        return label == null || label.isBlank() ? "column" + index : label;
    }

    /** Turns driver specific LOB wrappers into plain values so result sets can be closed safely. */
    private static @Nullable Object materialize(@Nullable Object value) throws SQLException {
        if (value instanceof java.sql.Clob clob) {
            return clob.getSubString(1, (int) Math.min(clob.length(), 1_000_000L));
        }
        if (value instanceof java.sql.Blob blob) {
            return blob.getBytes(1, (int) Math.min(blob.length(), 64_000L));
        }
        if (value instanceof java.sql.SQLXML xml) {
            return xml.getString();
        }
        if (value instanceof java.sql.Array array) {
            Object raw = array.getArray();
            array.free();
            return raw;
        }
        return value;
    }

    /** Lists the connectable databases on the server. */
    public static @NotNull List<String> listDatabases(@NotNull Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select datname from pg_database where datallowconn order by datname")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return List.copyOf(names);
    }

    /** Lists the schemas visible to the current user, excluding PostgreSQL internals. */
    public static @NotNull List<String> listSchemas(@NotNull Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select nspname from pg_namespace "
                             + "where nspname not like 'pg\\_%' and nspname <> 'information_schema' "
                             + "order by nspname")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return List.copyOf(names);
    }

    /** Lists tables and views for the schema browser tree. */
    public static @NotNull List<String> listRelations(@NotNull Connection connection, @NotNull String schema) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, schema, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    /** Server version banner, used in the status bar after connecting. */
    public static @NotNull String serverInfo(@NotNull Connection connection) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (SQLException e) {
            return "connected";
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static int safeInt(@NotNull SqlSupplier<Integer> supplier) {
        try {
            Integer v = supplier.get();
            return v == null ? java.sql.Types.OTHER : v;
        } catch (Exception e) {
            return java.sql.Types.OTHER;
        }
    }

    private static @Nullable String safeString(@NotNull SqlSupplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
