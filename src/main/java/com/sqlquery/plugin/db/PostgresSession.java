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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single live PostgreSQL session shared by the tool window.
 *
 * <p>Can hold a transaction open across statements: with auto-commit off the same
 * {@link Connection} is reused, and {@link #commit()} / {@link #rollback()} end the
 * transaction. All mutation happens on the UI thread while the connection is idle,
 * and the {@code volatile} fields make the state visible to the background
 * execution thread.</p>
 */
public final class PostgresSession implements AutoCloseable {

    private volatile Connection connection;
    private volatile ConnectionProfile profile;

    public boolean isConnected() {
        Connection c = connection;
        try {
            return c != null && !c.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public @Nullable ConnectionProfile profile() {
        return profile;
    }

    /** The live connection, or {@code null} when disconnected. */
    public @Nullable Connection connection() {
        return connection;
    }

    /** The live connection, failing fast when there is none. */
    public @NotNull Connection requireConnection() throws SQLException {
        Connection c = connection;
        if (c == null || c.isClosed()) {
            throw new SQLException("Not connected. Use Connect first.");
        }
        return c;
    }

    /** Raised when a session already exists; callers decide whether to close it first. */
    public void connect(@NotNull ConnectionProfile profile, @Nullable String password) throws SQLException {
        disconnect();
        this.connection = DriverResolver.open(profile, password);
        this.profile = profile;
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        requireConnection().setAutoCommit(autoCommit);
    }

    public boolean isAutoCommit() throws SQLException {
        return requireConnection().getAutoCommit();
    }

    public void commit() throws SQLException {
        requireConnection().commit();
    }

    public void rollback() throws SQLException {
        requireConnection().rollback();
    }

    /** Cancels the statement currently running on the server, if any. */
    public void cancelRunningStatement() {
        Connection c = connection;
        if (c == null) {
            return;
        }
        try {
            c.abort(Runnable::run);
        } catch (Throwable ignored) {
            // abort is best effort; closing the connection below is the real cancellation
        }
    }

    /** Databases available on the server, for the database selector. */
    public @NotNull List<String> databases() throws SQLException {
        return new ArrayList<>(SqlRunner.listDatabases(requireConnection()));
    }

    public @NotNull List<String> schemas() throws SQLException {
        return new ArrayList<>(SqlRunner.listSchemas(requireConnection()));
    }

    public @NotNull List<String> relations(@NotNull String schema) throws SQLException {
        return new ArrayList<>(SqlRunner.listRelations(requireConnection(), schema));
    }

    // ---------------------------------------------------------------- structure

    /** Schemas, tables, views, functions, triggers and the rest, for the schema browser. */
    public @NotNull List<String> schemaNames() throws SQLException {
        return DbStructureReader.schemas(requireConnection());
    }

    public @NotNull List<DbStructureReader.Relation> relationsOf(@NotNull String schema) throws SQLException {
        return DbStructureReader.relations(requireConnection(), schema);
    }

    public @NotNull List<DbStructureReader.Column> columnsOf(@NotNull String schema,
                                                            @NotNull String relation) throws SQLException {
        return DbStructureReader.columns(requireConnection(), schema, relation);
    }

    public @NotNull List<DbStructureReader.Index> indexesOf(@NotNull String schema,
                                                           @NotNull String relation) throws SQLException {
        return DbStructureReader.indexes(requireConnection(), schema, relation);
    }

    public @NotNull List<DbStructureReader.Routine> routinesOf(@NotNull String schema,
                                                              boolean procedures) throws SQLException {
        return DbStructureReader.routines(requireConnection(), schema, procedures);
    }

    public @NotNull List<DbStructureReader.Trigger> triggersOf(@NotNull String schema) throws SQLException {
        return DbStructureReader.triggers(requireConnection(), schema);
    }

    public @NotNull List<DbStructureReader.Sequence> sequencesOf(@NotNull String schema) throws SQLException {
        return DbStructureReader.sequences(requireConnection(), schema);
    }

    public @NotNull List<DbStructureReader.TypeInfo> typesOf(@NotNull String schema) throws SQLException {
        return DbStructureReader.types(requireConnection(), schema);
    }

    public @NotNull List<DbStructureReader.Extension> extensions() throws SQLException {
        return DbStructureReader.extensions(requireConnection());
    }

    public void disconnect() {
        Connection c = connection;
        connection = null;
        profile = null;
        if (c != null) {
            try {
                if (!c.getAutoCommit()) {
                    c.rollback();
                }
            } catch (SQLException ignored) {
                // connection already broken
            }
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing sensible left to do
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
