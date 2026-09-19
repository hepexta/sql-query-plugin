/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.temporal.TemporalAccessor;
import java.util.StringJoiner;

/**
 * Converts raw JDBC values into something a {@code JTable} can render and a user can copy.
 * Free of any IntelliJ API so it can be unit tested headlessly.
 */
public final class SqlValueFormatter {

    /** Placeholder written to the clipboard for SQL {@code NULL}. */
    public static final String NULL_TEXT = "NULL";

    private SqlValueFormatter() {
    }

    /** Renders a value for display. {@code null} becomes the string {@code NULL}. */
    public static @NotNull String display(@Nullable Object value) {
        if (value == null) {
            return NULL_TEXT;
        }
        if (value instanceof byte[] bytes) {
            return toHex(bytes);
        }
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof Blob blob) {
            return readBlob(blob);
        }
        if (value instanceof SQLXML xml) {
            try {
                return xml.getString();
            } catch (SQLException e) {
                return "<xml>";
            }
        }
        if (value instanceof Array array) {
            return formatArray(array);
        }
        // PostgreSQL array columns materialize as plain Java arrays in most drivers.
        if (value instanceof Object[] objects) {
            return formatObjects(objects);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        String text = String.valueOf(value);
        // Keep grid cells readable: collapse newlines and tabs from text columns.
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) {
            return text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n").replace("\t", "\\t");
        }
        return text;
    }

    /** Renders a value for the clipboard: SQL {@code NULL} stays distinguishable from an empty string. */
    public static @NotNull String clipboard(@Nullable Object value) {
        return display(value);
    }

    private static @NotNull String formatArray(@NotNull Array array) {
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] objects) {
                return formatObjects(objects);
            }
            return String.valueOf(raw);
        } catch (SQLException e) {
            return "{?}";
        }
    }

    private static @NotNull String formatObjects(Object @NotNull [] objects) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (Object o : objects) {
            joiner.add(display(o));
        }
        return joiner.toString();
    }

    private static @NotNull String readClob(@NotNull Clob clob) {
        try {
            long length = Math.min(clob.length(), 1_000_000L);
            return clob.getSubString(1, (int) length);
        } catch (SQLException e) {
            return "<clob>";
        }
    }

    private static @NotNull String readBlob(@NotNull Blob blob) {
        try {
            long length = Math.min(blob.length(), 64_000L);
            return toHex(blob.getBytes(1, (int) length));
        } catch (SQLException e) {
            return "<blob>";
        }
    }

    private static @NotNull String toHex(byte @NotNull [] bytes) {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("\\x");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
