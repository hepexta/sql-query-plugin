/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quotes PostgreSQL identifiers when they need it, and builds the read-only statements the
 * schema browser generates when an object is opened.
 *
 * <p>Quoting only when necessary keeps generated SQL readable:
 * {@code select * from public.agent limit 20} rather than
 * {@code select * from "public"."agent" limit 20}. PostgreSQL folds unquoted identifiers to
 * lower case, so any identifier that is not already lower case, or that is not a plain
 * {@code [a-z_][a-z0-9_$]*} word, must be quoted — and so must a reserved keyword, or
 * {@code select * from user} would read the session user instead of a table named {@code user}.</p>
 */
public final class SqlIdentifiers {

    private static final Pattern SAFE = Pattern.compile("[a-z_][a-z0-9_$]*");

    /**
     * Keywords that must be quoted when they appear as an identifier in generated SQL.
     *
     * <p>This is a safety net, not a parser. It is the PostgreSQL reserved-keyword list plus
     * the clause-introducing words ({@code order}, {@code limit}, {@code group}, {@code offset})
     * which, although officially non-reserved, are syntactically special inside a
     * {@code FROM} clause and read confusingly unquoted. Quoting a non-reserved word is always
     * legal, so extra entries cost nothing while a missing one produces broken SQL.</p>
     */
    private static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "both", "case", "cast", "check", "collate", "column", "constraint", "create",
            "current_catalog", "current_date", "current_role", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "false", "fetch", "for", "foreign", "from", "grant",
            "group", "having", "in", "initially", "intersect", "into", "lateral", "leading",
            "limit", "localtime", "localtimestamp", "not", "null", "offset", "on", "only",
            "or", "order", "placing", "primary", "references", "returning", "select",
            "session_user", "some", "symmetric", "table", "then", "to", "trailing", "true",
            "union", "unique", "user", "using", "variadic", "when", "where", "window", "with");

    private SqlIdentifiers() {
    }

    /** Wraps an identifier in double quotes, escaping embedded quotes. */
    public static @NotNull String quote(@Nullable String identifier) {
        String value = identifier == null ? "" : identifier;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** True when the identifier must be quoted to keep its exact spelling and meaning. */
    public static boolean needsQuoting(@Nullable String identifier) {
        String value = identifier == null ? "" : identifier;
        return !SAFE.matcher(value).matches() || RESERVED.contains(value);
    }

    /** Quotes only when PostgreSQL would otherwise fold, reject or reinterpret the identifier. */
    public static @NotNull String maybeQuote(@Nullable String identifier) {
        return needsQuoting(identifier) ? quote(identifier) : (identifier == null ? "" : identifier);
    }

    /** {@code schema.name}, each part quoted only if needed. */
    public static @NotNull String qualified(@Nullable String schema, @Nullable String name) {
        if (schema == null || schema.isBlank()) {
            return maybeQuote(name);
        }
        return maybeQuote(schema) + "." + maybeQuote(name);
    }

    // ------------------------------------------------------------ generated SQL

    /** Default row cap for browser-generated SELECTs. */
    public static final int PREVIEW_LIMIT = 20;

    /** {@code select * from <relation> limit 20;} */
    public static @NotNull String selectPreview(@Nullable String schema, @Nullable String relation) {
        return selectPreview(schema, relation, PREVIEW_LIMIT);
    }

    public static @NotNull String selectPreview(@Nullable String schema, @Nullable String relation, int limit) {
        return "select * from " + qualified(schema, relation) + " limit " + Math.max(1, limit) + ";";
    }

    /** {@code select * from <function>(<args>) limit 20;} — a starting point for a routine. */
    public static @NotNull String callRoutine(@Nullable String schema, @Nullable String name) {
        return "select * from " + qualified(schema, name) + "() limit " + PREVIEW_LIMIT + ";";
    }

    /** {@code select * from <sequence>;} — reading a sequence yields its current value. */
    public static @NotNull String sequenceValue(@Nullable String schema, @Nullable String sequence) {
        return "select * from " + qualified(schema, sequence) + ";";
    }

    /** A ready-to-run skeleton for creating a trigger on a relation. */
    public static @NotNull String triggerSkeleton(@Nullable String schema, @Nullable String relation) {
        return "-- create a trigger on " + qualified(schema, relation) + "\n"
                + "select tgname, pg_get_triggerdef(oid) as definition\n"
                + "from pg_trigger\n"
                + "where tgrelid = '" + qualified(schema, relation) + "'::regclass\n"
                + "  and not tgisinternal;";
    }

    /** {@code select * from information_schema.columns where ...}, used for a schema node. */
    public static @NotNull String schemaOverview(@Nullable String schema) {
        return "select table_name, column_name, data_type, is_nullable\n"
                + "from information_schema.columns\n"
                + "where table_schema = '" + (schema == null ? "" : schema.replace("'", "''")) + "'\n"
                + "order by table_name, ordinal_position;";
    }

    /** A clamped {@code limit} expression for user-visible text. */
    public static @NotNull String limitClause(int limit) {
        return "limit " + Math.max(1, limit);
    }
}
