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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the structure of a PostgreSQL database for the schema browser: schemas, and within
 * each schema its tables, views, functions, procedures, triggers, sequences, types and
 * extensions.
 *
 * <p>Every query here is read-only and works on PostgreSQL 11 and later. Nothing is cached:
 * the browser pulls on expand, so it always reflects the live server.</p>
 *
 * <p>Deliberately free of IntelliJ APIs, so it can be exercised by the headless integration
 * checks against a real server.</p>
 */
public final class DbStructureReader {

    private DbStructureReader() {
    }

    // ------------------------------------------------------------------- shapes

    /** A relation (table, view, materialised view, partitioned table or foreign table). */
    public record Relation(
            @NotNull String schema,
            @NotNull String name,
            @NotNull String kind,
            long estimatedRows,
            @Nullable String comment) {

        public boolean isTableLike() {
            return "TABLE".equals(kind) || "PARTITIONED TABLE".equals(kind) || "FOREIGN TABLE".equals(kind);
        }

        public @NotNull String qualifiedName() {
            return SqlIdentifiers.qualified(schema, name);
        }
    }

    /** A column of a relation. */
    public record Column(
            @NotNull String name,
            @NotNull String dataType,
            boolean nullable,
            @Nullable String defaultValue,
            @Nullable String comment) {
    }

    /** An index or constraint backing an index. */
    public record Index(
            @NotNull String name,
            @NotNull String definition,
            boolean primary,
            boolean unique) {
    }

    /** A function or procedure. Argument types are included so overloads stay distinguishable. */
    public record Routine(
            @NotNull String schema,
            @NotNull String name,
            @NotNull String kind,
            @NotNull String arguments,
            @NotNull String returnType,
            @Nullable String comment) {

        public @NotNull String signature() {
            return arguments.isBlank() ? name + "()" : name + "(" + arguments + ")";
        }

        public boolean isProcedure() {
            return kind.startsWith("PROCEDURE");
        }
    }

    /** A trigger, attached to a relation. */
    public record Trigger(
            @NotNull String name,
            @NotNull String relation,
            @NotNull String timing,
            @NotNull String event,
            @NotNull String function) {

        public @NotNull String description() {
            return timing + " " + event + " \u2192 " + function;
        }
    }

    /** A sequence. */
    public record Sequence(
            @NotNull String schema,
            @NotNull String name,
            @NotNull String dataType,
            @NotNull String startValue) {
    }

    /** A composite, enum, range or domain type. */
    public record TypeInfo(
            @NotNull String schema,
            @NotNull String name,
            @NotNull String kind,
            @Nullable String comment) {
    }

    /** An installed extension. */
    public record Extension(
            @NotNull String name,
            @NotNull String version,
            @Nullable String schema) {
    }

    // ------------------------------------------------------------------ queries

    /** All schemas the current user can see, excluding PostgreSQL internals. */
    public static @NotNull List<String> schemas(@NotNull Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "select nspname from pg_namespace "
                + "where nspname not like 'pg\\_%' and nspname <> 'information_schema' "
                + "order by nspname";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** Relations in a schema, ordered tables first then by name. */
    public static @NotNull List<Relation> relations(@NotNull Connection connection,
                                                    @NotNull String schema) throws SQLException {
        String sql = "select c.relname, "
                + "case c.relkind when 'r' then 'TABLE' when 'p' then 'PARTITIONED TABLE' "
                + "  when 'v' then 'VIEW' when 'm' then 'MATERIALIZED VIEW' when 'f' then 'FOREIGN TABLE' end, "
                + "case when c.relkind in ('r','p','m') then c.reltuples::bigint else null end, "
                + "obj_description(c.oid, 'pg_class') "
                + "from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = ? and c.relkind in ('r','p','v','m','f') "
                + "order by case c.relkind when 'r' then 0 when 'p' then 1 when 'm' then 2 "
                + "  when 'v' then 3 else 4 end, c.relname";
        List<Relation> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Relation(schema, rs.getString(1), rs.getString(2),
                            rs.getLong(3), rs.getString(4)));
                }
            }
        }
        return result;
    }

    /** Columns of a relation, in ordinal position order. */
    public static @NotNull List<Column> columns(@NotNull Connection connection,
                                                @NotNull String schema,
                                                @NotNull String relation) throws SQLException {
        String sql = "select a.attname, "
                + "format_type(a.atttypid, a.atttypmod), "
                + "not a.attnotnull, "
                + "pg_get_expr(d.adbin, d.adrelid), "
                + "col_description(a.attrelid, a.attnum) "
                + "from pg_attribute a "
                + "join pg_class c on c.oid = a.attrelid "
                + "join pg_namespace n on n.oid = c.relnamespace "
                + "left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum "
                + "where n.nspname = ? and c.relname = ? and a.attnum > 0 and not a.attisdropped "
                + "order by a.attnum";
        List<Column> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, relation);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Column(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        }
        return result;
    }

    /** Indexes on a relation, primary keys first. */
    public static @NotNull List<Index> indexes(@NotNull Connection connection,
                                               @NotNull String schema,
                                               @NotNull String relation) throws SQLException {
        String sql = "select i.relname, pg_get_indexdef(ix.indexrelid), ix.indisprimary, ix.indisunique "
                + "from pg_index ix "
                + "join pg_class i on i.oid = ix.indexrelid "
                + "join pg_class t on t.oid = ix.indrelid "
                + "join pg_namespace n on n.oid = t.relnamespace "
                + "where n.nspname = ? and t.relname = ? "
                + "order by ix.indisprimary desc, i.relname";
        List<Index> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, relation);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Index(rs.getString(1), rs.getString(2),
                            rs.getBoolean(3), rs.getBoolean(4)));
                }
            }
        }
        return result;
    }

    /** Functions and procedures in a schema. */
    public static @NotNull List<Routine> routines(@NotNull Connection connection,
                                                  @NotNull String schema,
                                                  boolean procedures) throws SQLException {
        String sql = "select p.proname, "
                + "case p.prokind when 'p' then 'PROCEDURE' when 'f' then 'FUNCTION' "
                + "  when 'a' then 'AGGREGATE' when 'w' then 'WINDOW' else 'FUNCTION' end, "
                + "pg_get_function_arguments(p.oid), "
                + "case when p.prokind = 'p' then 'void' else pg_get_function_result(p.oid) end, "
                + "obj_description(p.oid, 'pg_proc') "
                + "from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = ? and p.prokind " + (procedures ? "= 'p'" : "<> 'p'")
                + " order by p.proname";
        List<Routine> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Routine(schema, rs.getString(1), rs.getString(2),
                            nullToEmpty(rs.getString(3)), nullToEmpty(rs.getString(4)), rs.getString(5)));
                }
            }
        }
        return result;
    }

    /** Triggers attached to the relations of a schema. */
    public static @NotNull List<Trigger> triggers(@NotNull Connection connection,
                                                  @NotNull String schema) throws SQLException {
        String sql = "select t.tgname, c.relname, "
                + "case when (t.tgtype & 2) <> 0 then 'BEFORE' "
                + "     when (t.tgtype & 64) <> 0 then 'INSTEAD OF' else 'AFTER' end, "
                + "trim(both ' OR' from concat_ws(' OR', "
                + "  case when (t.tgtype & 4) <> 0 then 'INSERT' end, "
                + "  case when (t.tgtype & 8) <> 0 then 'DELETE' end, "
                + "  case when (t.tgtype & 16) <> 0 then 'UPDATE' end, "
                + "  case when (t.tgtype & 32) <> 0 then 'TRUNCATE' end)), "
                + "p.proname "
                + "from pg_trigger t "
                + "join pg_class c on c.oid = t.tgrelid "
                + "join pg_namespace n on n.oid = c.relnamespace "
                + "join pg_proc p on p.oid = t.tgfoid "
                + "where n.nspname = ? and not t.tgisinternal "
                + "order by c.relname, t.tgname";
        List<Trigger> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Trigger(rs.getString(1), rs.getString(2),
                            rs.getString(3), nullToEmpty(rs.getString(4)), rs.getString(5)));
                }
            }
        }
        return result;
    }

    /** Sequences in a schema. */
    public static @NotNull List<Sequence> sequences(@NotNull Connection connection,
                                                    @NotNull String schema) throws SQLException {
        String sql = "select c.relname, format_type(s.seqtypid, null), s.seqstart::text "
                + "from pg_sequence s "
                + "join pg_class c on c.oid = s.seqrelid "
                + "join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = ? order by c.relname";
        List<Sequence> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Sequence(schema, rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return result;
    }

    /** Composite, enum, range and domain types in a schema. */
    public static @NotNull List<TypeInfo> types(@NotNull Connection connection,
                                                @NotNull String schema) throws SQLException {
        String sql = "select t.typname, "
                + "case t.typtype when 'c' then 'COMPOSITE' when 'e' then 'ENUM' "
                + "  when 'd' then 'DOMAIN' when 'r' then 'RANGE' when 'm' then 'MULTIRANGE' else 'TYPE' end, "
                + "obj_description(t.oid, 'pg_type') "
                + "from pg_type t join pg_namespace n on n.oid = t.typnamespace "
                + "where n.nspname = ? and t.typtype in ('c','e','d','r','m') "
                + "and not exists (select 1 from pg_class c where c.oid = t.typrelid and c.relkind <> 'c') "
                + "order by t.typname";
        List<TypeInfo> result = new ArrayList<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new TypeInfo(schema, rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return result;
    }

    /** Installed extensions. */
    public static @NotNull List<Extension> extensions(@NotNull Connection connection) throws SQLException {
        String sql = "select e.extname, e.extversion, n.nspname "
                + "from pg_extension e left join pg_namespace n on n.oid = e.extnamespace "
                + "order by e.extname";
        List<Extension> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new Extension(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return result;
    }

    private static @NotNull String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
