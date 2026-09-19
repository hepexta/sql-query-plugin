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
