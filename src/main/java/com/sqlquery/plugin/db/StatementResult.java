/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Outcome of executing a single SQL statement. */
public sealed interface StatementResult {

    /** The statement that produced this result. */
    @NotNull String sql();

    /** Wall clock duration of the execution, in milliseconds. */
    long elapsedMillis();

    /** A result set returned by a {@code SELECT} (or any statement that returns rows). */
    record QueryResult(
            @NotNull String sql,
            @NotNull List<Column> columns,
            @NotNull List<Object[]> rows,
            boolean truncated,
            long elapsedMillis) implements StatementResult {

        public int rowCount() {
            return rows.size();
        }

        /** Title used for the results tab, e.g. {@code 12 rows}. */
        public @NotNull String summary() {
            return (truncated ? "\u2265 " : "") + rowCount() + (rowCount() == 1 ? " row" : " rows");
        }
    }

    /** Outcome of a statement that does not return rows. */
    record UpdateResult(
            @NotNull String sql,
            long affectedRows,
            long elapsedMillis) implements StatementResult {

        public @NotNull String summary() {
            if (affectedRows < 0) {
                return "completed";
            }
            return affectedRows + (affectedRows == 1 ? " row affected" : " rows affected");
        }
    }

    /** Column metadata carried alongside a result set. */
    record Column(@NotNull String name, @NotNull String typeName) {

        /** Label shown in the header, e.g. {@code id : int4}. */
        public @NotNull String header() {
            return typeName.isEmpty() ? name : name + " : " + typeName;
        }
    }

    /** Convenience factory used by tests and by the runner. */
    static @NotNull QueryResult query(@NotNull String sql, @NotNull List<Column> columns,
                                      @NotNull List<Object[]> rows, boolean truncated, long elapsed) {
        return new QueryResult(sql, columns, rows, truncated, elapsed);
    }

    static @Nullable String typeNameOf(int sqlType, @Nullable String driverTypeName) {
        if (driverTypeName != null && !driverTypeName.isBlank()) {
            return driverTypeName;
        }
        return switch (sqlType) {
            case java.sql.Types.INTEGER -> "int4";
            case java.sql.Types.BIGINT -> "int8";
            case java.sql.Types.SMALLINT -> "int2";
            case java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR -> "varchar";
            case java.sql.Types.CHAR -> "bpchar";
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> "numeric";
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "bool";
            case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "timestamptz";
            case java.sql.Types.DATE -> "date";
            case java.sql.Types.TIME -> "time";
            case java.sql.Types.ARRAY -> "array";
            case java.sql.Types.OTHER -> "other";
            default -> "";
        };
    }
}
