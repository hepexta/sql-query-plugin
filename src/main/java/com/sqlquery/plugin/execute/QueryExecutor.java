/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.execute;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.sqlquery.plugin.db.ConnectionProfile;
import com.sqlquery.plugin.db.PostgresSession;
import com.sqlquery.plugin.db.SqlRunner;
import com.sqlquery.plugin.db.SqlSplitter;
import com.sqlquery.plugin.db.StatementResult;
import com.sqlquery.plugin.settings.SqlQuerySettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owns the {@link PostgresSession} for a tool window and runs SQL on a background thread
 * with a cancellable progress bar.
 *
 * <p>Call the public methods from the EDT. JDBC work happens on a pooled thread; callbacks
 * are invoked back on the EDT by the platform.</p>
 */
public final class QueryExecutor {

    private final PostgresSession session = new PostgresSession();
    private volatile boolean cancelRequested;

    public @NotNull PostgresSession session() {
        return session;
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    // ---------------------------------------------------------------- connecting

    /**
     * Connects using the given profile. {@code password} is used when non-null, otherwise the
     * password is read from the credential store.
     */
    public void connect(@NotNull Project project,
                        @NotNull ConnectionProfile profile,
                        @Nullable String password,
                        @NotNull Consumer<String> onSuccess,
                        @NotNull Consumer<String> onError) {
        String effectivePassword = password != null
                ? password
                : SqlQuerySettings.getInstance().getPassword(profile.getName());
        boolean autoCommit = SqlQuerySettings.getInstance().isAutoCommit();

        run(project, "Connecting to " + profile.describeTarget(), indicator -> {
            session.connect(profile, effectivePassword);
            session.setAutoCommit(autoCommit);
            return SqlRunner.serverInfo(session.requireConnection());
        }, onSuccess, onError);
    }

    public void disconnect() {
        cancelRequested = true;
        session.disconnect();
        cancelRequested = false;
    }

    /** Lists databases for the database selector; empty when not connected. */
    public @NotNull List<String> listDatabases() {
        if (!session.isConnected()) {
            return List.of();
        }
        try {
            return session.databases();
        } catch (SQLException e) {
            return List.of();
        }
    }

    public @NotNull List<String> listSchemas() {
        if (!session.isConnected()) {
            return List.of();
        }
        try {
            return session.schemas();
        } catch (SQLException e) {
            return List.of();
        }
    }

    public @NotNull List<String> listRelations(@NotNull String schema) {
        if (!session.isConnected()) {
            return List.of();
        }
        try {
            return session.relations(schema);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** Switches to another database on the same server by reconnecting. */
    public void switchDatabase(@NotNull Project project,
                               @NotNull String database,
                               @NotNull Runnable onSuccess,
                               @NotNull Consumer<String> onError) {
        ConnectionProfile current = session.profile();
        if (current == null) {
            onError.accept("Not connected.");
            return;
        }
        ConnectionProfile target = ConnectionProfile.copyOf(current);
        target.setDatabase(database);
        target.setUrlOverride("");
        String password = SqlQuerySettings.getInstance().getPassword(target.getName());
        boolean autoCommit = SqlQuerySettings.getInstance().isAutoCommit();

        run(project, "Switching to database " + database, indicator -> {
            session.connect(target, password);
            session.setAutoCommit(autoCommit);
            return "Connected to " + database;
        }, message -> onSuccess.run(), onError);
    }

    // ----------------------------------------------------------- transactions

    public void setAutoCommit(boolean autoCommit, @NotNull Consumer<String> onError) {
        try {
            session.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            onError.accept(message(e));
        }
    }

    public void commit(@NotNull Consumer<String> onError) {
        try {
            session.commit();
        } catch (SQLException e) {
            onError.accept(message(e));
        }
    }

    public void rollback(@NotNull Consumer<String> onError) {
        try {
            session.rollback();
        } catch (SQLException e) {
            onError.accept(message(e));
        }
    }

    // -------------------------------------------------------------- executing

    /** Called from the EDT to stop the statement currently running on the server. */
    public void cancel() {
        cancelRequested = true;
        session.cancelRunningStatement();
    }

    /**
     * Splits {@code script} and executes every statement in order on a background thread.
     *
     * @param fetchLimit     maximum rows read per result set
     * @param timeoutSeconds per-statement timeout
     */
    public void executeScript(@NotNull Project project,
                              @NotNull String script,
                              int fetchLimit,
                              int timeoutSeconds,
                              @NotNull Consumer<ExecutionReport> onSuccess,
                              @NotNull Consumer<String> onError) {
        List<SqlSplitter.Statement> statements = SqlSplitter.split(script);
        if (statements.isEmpty()) {
            onError.accept("Nothing to execute.");
            return;
        }

        String title = "Executing " + statements.size()
                + (statements.size() == 1 ? " statement" : " statements");

        run(project, title, indicator -> {
            List<StatementResult> results = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                checkCancelled();
                indicator.checkCanceled();
                indicator.setText2("Statement " + (i + 1) + " of " + statements.size());

                Connection connection = session.requireConnection();
                StatementResult result;
                try {
                    result = SqlRunner.execute(connection, statements.get(i).sql(), fetchLimit, timeoutSeconds);
                } catch (SQLException e) {
                    if (cancelRequested) {
                        throw new CancelledException("Execution cancelled.");
                    }
                    throw new StatementFailureException(e, i, statements.get(i), results);
                }
                results.add(result);
                checkCancelled();
            }
            return new ExecutionReport(results);
        }, report -> onSuccess.accept(report), onError);
    }

    private void checkCancelled() {
        if (cancelRequested) {
            throw new CancelledException("Execution cancelled.");
        }
    }

    /** Result payload handed back to the UI. */
    public record ExecutionReport(@NotNull List<StatementResult> results) {

        public @NotNull String summarise() {
            long rows = 0;
            long affected = 0;
            int queries = 0;
            long elapsed = 0;
            for (StatementResult result : results) {
                elapsed += result.elapsedMillis();
                if (result instanceof StatementResult.QueryResult q) {
                    queries++;
                    rows += q.rowCount();
                } else if (result instanceof StatementResult.UpdateResult u) {
                    affected += Math.max(0, u.affectedRows());
                }
            }
            StringBuilder sb = new StringBuilder();
            if (queries > 0) {
                sb.append(rows).append(rows == 1 ? " row" : " rows").append(" returned");
            }
            if (affected > 0 || queries == 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(affected).append(affected == 1 ? " row" : " rows").append(" affected");
            }
            sb.append(" in ").append(elapsed).append(" ms");
            if (results.size() > 1) {
                sb.append("   \u2022   ").append(results.size()).append(" statements");
            }
            return sb.toString();
        }
    }

    /** Thrown to unwind cleanly when the user cancels; never reported as an error dialog. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }

    /** A SQL error together with the results of the statements that already succeeded. */
    public static final class StatementFailureException extends RuntimeException {
        private final transient List<StatementResult> partialResults;
        private final int statementIndex;
        private final String statementPreview;

        StatementFailureException(@NotNull SQLException cause, int index,
                                  @NotNull SqlSplitter.Statement statement,
                                  @NotNull List<StatementResult> partialResults) {
            super(message(cause), cause);
            this.statementIndex = index;
            this.statementPreview = statement.preview(80);
            this.partialResults = List.copyOf(partialResults);
        }

        public @NotNull List<StatementResult> partialResults() {
            return partialResults;
        }

        public int statementIndex() {
            return statementIndex;
        }

        public @NotNull String statementPreview() {
            return statementPreview;
        }

        public @Nullable String sqlState() {
            Throwable cause = getCause();
            return cause instanceof SQLException sqlException ? sqlException.getSQLState() : null;
        }
    }

    private static @NotNull String message(@NotNull Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    // -------------------------------------------------------------- background

    /** Work performed on a background thread; returns the value handed to {@code onSuccess}. */
    private interface Work<T> {
        @NotNull T run(@NotNull ProgressIndicator indicator) throws Exception;
    }

    private <T> void run(@NotNull Project project,
                         @NotNull String title,
                         @NotNull Work<T> work,
                         @NotNull Consumer<? super T> onSuccess,
                         @NotNull Consumer<String> onError) {
        cancelRequested = false;
        new Task.Backgroundable(project, title, true) {
            private T result;
            private Throwable failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result = work.run(indicator);
                } catch (Throwable t) {
                    failure = t;
                }
            }

            @Override
            public void onCancel() {
                cancelRequested = true;
                // Closing the connection is what actually stops the server side work.
                session.cancelRunningStatement();
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    reportFailure(failure, onError);
                } else {
                    onSuccess.accept(result);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                reportFailure(error, onError);
            }
        }.queue();
    }

    private void reportFailure(@NotNull Throwable error, @NotNull Consumer<String> onError) {
        if (error instanceof CancelledException) {
            onError.accept(error.getMessage());
        } else {
            onError.accept(message(error));
        }
    }
}
