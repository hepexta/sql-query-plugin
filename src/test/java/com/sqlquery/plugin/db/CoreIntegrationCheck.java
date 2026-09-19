/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;

/**
 * Headless verification of the JDBC layer against a real PostgreSQL server.
 *
 * <p>Runs as a plain program (see {@link #main}) and as the engine behind
 * {@link CoreIntegrationTest}, so the same coverage is available from the command line and from
 * {@code gradlew test}.</p>
 */
public final class CoreIntegrationCheck {

    /** Receives every check result: {@code passed} plus a human readable description. */
    @FunctionalInterface
    public interface Reporter extends BiConsumer<Boolean, String> {
    }

    /** Collects checks so a test can assert on them all at once. */
    public static final class Results {
        private final List<String> failures = new ArrayList<>();
        private int passed;

        /** Records one check outcome. */
        public void record(boolean ok, @NotNull String what) {
            if (ok) {
                passed++;
            } else {
                failures.add(what);
            }
        }

        public int passed() {
            return passed;
        }

        public List<String> failures() {
            return List.copyOf(failures);
        }

        public int total() {
            return passed + failures.size();
        }
    }

    private CoreIntegrationCheck() {
    }

    /** Connection settings, overridable with {@code -Dpg.*} system properties. */
    public record Settings(String host, int port, String database, String user, String password) {

        public static Settings fromSystemProperties() {
            return new Settings(
                    System.getProperty("pg.host", "localhost"),
                    Integer.parseInt(System.getProperty("pg.port", "5432")),
                    System.getProperty("pg.database", "allagents"),
                    System.getProperty("pg.user", "agents"),
                    System.getProperty("pg.password", "agents"));
        }

        public ConnectionProfile profile() {
            ConnectionProfile profile = new ConnectionProfile();
            profile.setName("integration");
            profile.setHost(host);
            profile.setPort(port);
            profile.setDatabase(database);
            profile.setUser(user);
            profile.setSslMode("prefer");
            profile.setStatementTimeoutSeconds(30);
            profile.setFetchLimit(500);
            return profile;
        }
    }

    /** Runs every check, reporting each outcome to {@code reporter}. Returns the number run. */
    public static int run(Settings settings, Reporter reporter) {
        Counter counter = new Counter(reporter);
        ConnectionProfile profile = settings.profile();
        counter.check(true, "URL " + profile.effectiveUrl());

        counter.check(true, "===== SqlSplitter =====");
        splitterChecks(counter);
        counter.check(true, "===== SqlValueFormatter =====");
        formatterChecks(counter);
        counter.check(true, "===== Connection =====");
        databaseChecks(profile, settings.password(), counter);
        return counter.count;
    }

    /** Counts how many checks were performed, so callers can detect a silently skipped run. */
    private static final class Counter implements Reporter {
        private final Reporter delegate;
        private int count;

        Counter(Reporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(Boolean ok, String what) {
            count++;
            delegate.accept(ok, what);
        }

        void check(boolean ok, String what) {
            accept(ok, what);
        }
    }

    private static void splitterChecks(Reporter reporter) {
        check(reporter, "empty script -> no statements", SqlSplitter.split("   \n  -- just a comment\n").isEmpty());
        check(reporter, "single statement", SqlSplitter.split("select 1").size() == 1);
        check(reporter, "trailing semicolon", SqlSplitter.split("select 1;").size() == 1);
        check(reporter, "two statements", SqlSplitter.split("select 1; select 2;").size() == 2);
        check(reporter, "semicolon in string literal",
                SqlSplitter.split("insert into t values ('a;b');").size() == 1);
        check(reporter, "escaped quote inside literal",
                SqlSplitter.split("insert into t values ('it''s; fine');").size() == 1);
        check(reporter, "semicolon in line comment",
                SqlSplitter.split("select 1 -- a; b\n;").size() == 1);
        check(reporter, "semicolon in block comment",
                SqlSplitter.split("select 1 /* a; b */ ;").size() == 1);
        check(reporter, "nested block comment",
                SqlSplitter.split("select 1 /* outer /* inner; */ still; */ ;").size() == 1);
        check(reporter, "semicolon in dollar quote",
                SqlSplitter.split("do $$ begin perform 1; perform 2; end $$;").size() == 1);
        check(reporter, "tagged dollar quote",
                SqlSplitter.split("select $tag$ a;b $tag$;").size() == 1);
        check(reporter, "quoted identifier with semicolon",
                SqlSplitter.split("select \"we;ird\" from t;").size() == 1);

        String multiScript = "create temp table z(i int);\ninsert into z values (1),(2);\nselect * from z;";
        List<SqlSplitter.Statement> multi = SqlSplitter.split(multiScript);
        check(reporter, "three statements parsed", multi.size() == 3);
        check(reporter, "offsets round-trip",
                multi.stream().allMatch(s -> s.sql().equals(multiScript.substring(s.startOffset(), s.endOffset()))));
        check(reporter, "preview collapses whitespace",
                SqlSplitter.split("select\n  1;").get(0).preview(40).equals("select 1"));
        check(reporter, "preview truncates",
                SqlSplitter.of("select 1234567890").preview(8).endsWith("\u2026"));
    }

    private static void formatterChecks(Reporter reporter) {
        check(reporter, "null renders as NULL", "NULL".equals(SqlValueFormatter.display(null)));
        check(reporter, "empty string stays empty", "".equals(SqlValueFormatter.display("")));
        check(reporter, "integer", "42".equals(SqlValueFormatter.display(42)));
        check(reporter, "newline escaped", "a\\nb".equals(SqlValueFormatter.display("a\nb")));
        check(reporter, "binary as hex", "\\x0aff".equals(SqlValueFormatter.display(new byte[]{10, (byte) 0xff})));
        check(reporter, "array in braces",
                "{1, NULL, 3}".equals(SqlValueFormatter.display(new Object[]{1, null, 3})));
    }

    private static void databaseChecks(ConnectionProfile profile, String password, Reporter reporter) {
        try (PostgresSession session = new PostgresSession()) {
            session.connect(profile, password);
            check(reporter, "connected", session.isConnected());
            check(reporter, "server version reported", !SqlRunner.serverInfo(session.requireConnection()).isBlank());
            report(reporter, true, "server " + SqlRunner.serverInfo(session.requireConnection()));

            List<String> databases = session.databases();
            check(reporter, "databases listed", databases.contains(profile.getDatabase()));
            List<String> schemas = session.schemas();
            check(reporter, "public schema listed", schemas.contains("public"));
            List<String> relations = session.relations("public");
            check(reporter, "relations listed (" + relations.size() + ")", !relations.isEmpty());

            runnerChecks(session, reporter);
            errorChecks(session, reporter);
            transactionChecks(session, reporter);

            session.connect(profile, password);
            check(reporter, "reconnected", session.isConnected());
            session.disconnect();
            check(reporter, "disconnected", !session.isConnected());
        } catch (Exception e) {
            check(reporter, "database session failed: " + e.getMessage(), false);
        }

        section(reporter, "DriverResolver");
        ConnectionProfile unreachable = new ConnectionProfile();
        unreachable.setHost("127.0.0.1");
        unreachable.setPort(1);
        unreachable.setDatabase("nope");
        unreachable.setUser("nope");
        try {
            Connection connection = DriverResolver.open(unreachable, "nope");
            connection.close();
            check(reporter, "unreachable port must fail", false);
        } catch (java.sql.SQLException e) {
            check(reporter, "unreachable port fails cleanly", true);
        }
        check(reporter, "validation catches missing database", invalid("localhost", "", "u") != null);
        check(reporter, "validation catches missing user", invalid("localhost", "db", "") != null);
        check(reporter, "blank host defaults to localhost", "localhost".equals(blankHost().getHost()));
        check(reporter, "validation catches bad url", invalidWithUrl("jdbc:mysql://x/y") != null);
        check(reporter, "valid profile passes validation", invalid("localhost", "postgres", "postgres") == null);
    }

    private static void runnerChecks(PostgresSession session, Reporter reporter) {
        try {
            StatementResult r1 = SqlRunner.execute(session.requireConnection(), "select 1 as one, 'x' as s", 100, 30);
            check(reporter, "select returns QueryResult", r1 instanceof StatementResult.QueryResult);
            if (r1 instanceof StatementResult.QueryResult q) {
                check(reporter, "one row", q.rowCount() == 1);
                check(reporter, "two columns", q.columns().size() == 2);
                check(reporter, "column label", "one".equals(q.columns().get(0).name()));
                check(reporter, "column type from driver", "int4".equals(q.columns().get(0).typeName()));
                check(reporter, "value rendered", "1".equals(SqlValueFormatter.display(q.rows().get(0)[0])));
                check(reporter, "row summary", "1 row".equals(q.summary()));
            }

            StatementResult ddl = SqlRunner.execute(session.requireConnection(),
                    "create temp table plugin_check(id int primary key, note text)", 100, 30);
            check(reporter, "DDL returns UpdateResult", ddl instanceof StatementResult.UpdateResult);

            StatementResult insert = SqlRunner.execute(session.requireConnection(),
                    "insert into plugin_check values (1,'a'),(2,'b'),(3,null)", 100, 30);
            check(reporter, "insert reports 3 affected rows",
                    insert instanceof StatementResult.UpdateResult u && u.affectedRows() == 3);

            StatementResult rows = SqlRunner.execute(session.requireConnection(),
                    "select * from plugin_check order by id", 100, 30);
            check(reporter, "rows read back", rows instanceof StatementResult.QueryResult q && q.rowCount() == 3);
            if (rows instanceof StatementResult.QueryResult q) {
                check(reporter, "SQL null preserved", q.rows().get(2)[1] == null);
                check(reporter, "text value read", "a".equals(q.rows().get(0)[1]));
            }

            StatementResult limited = SqlRunner.execute(session.requireConnection(),
                    "select * from plugin_check order by id", 2, 30);
            check(reporter, "fetch limit truncates",
                    limited instanceof StatementResult.QueryResult q && q.rowCount() == 2 && q.truncated());
            check(reporter, "truncated summary marked",
                    limited instanceof StatementResult.QueryResult q && q.summary().startsWith("\u2265"));

            String script = "select count(*) as c from plugin_check;\nselect id, note from plugin_check order by id;\n";
            List<SqlSplitter.Statement> parts = SqlSplitter.split(script);
            check(reporter, "script split into 2 statements", parts.size() == 2);
            for (SqlSplitter.Statement part : parts) {
                StatementResult res = SqlRunner.execute(session.requireConnection(), part.sql(), 500, 30);
                check(reporter, "script part executed: " + part.preview(40),
                        res instanceof StatementResult.QueryResult);
            }
        } catch (Exception e) {
            check(reporter, "SQL execution failed: " + e.getMessage(), false);
        }
    }

    private static void errorChecks(PostgresSession session, Reporter reporter) {
        try {
            SqlRunner.execute(session.requireConnection(), "select * from does_not_exist_xyz", 100, 30);
            check(reporter, "bad SQL raises SQLException", false);
        } catch (java.sql.SQLException e) {
            check(reporter, "bad SQL raises SQLException", true);
            report(reporter, true, "server said: " + e.getMessage().split("\n")[0]);
        } catch (Exception e) {
            check(reporter, "bad SQL raises SQLException", false);
        }
    }

    private static void transactionChecks(PostgresSession session, Reporter reporter) {
        try {
            session.setAutoCommit(false);
            check(reporter, "auto-commit turned off", !session.isAutoCommit());
            SqlRunner.execute(session.requireConnection(),
                    "create temp table tx_check(v int) on commit drop", 100, 30);
            SqlRunner.execute(session.requireConnection(), "insert into tx_check values (7)", 100, 30);
            StatementResult inTx = SqlRunner.execute(session.requireConnection(),
                    "select count(*) from tx_check", 100, 30);
            check(reporter, "row visible inside the transaction",
                    inTx instanceof StatementResult.QueryResult q
                            && "1".equals(SqlValueFormatter.display(q.rows().get(0)[0])));
            session.rollback();
            check(reporter, "still connected after rollback", session.isConnected());
            session.setAutoCommit(true);
            check(reporter, "auto-commit restored", session.isAutoCommit());
        } catch (Exception e) {
            check(reporter, "transaction handling failed: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String invalid(String host, String db, String user) {
        ConnectionProfile p = new ConnectionProfile();
        p.setHost(host);
        p.setDatabase(db);
        p.setUser(user);
        return p.validationError();
    }

    private static ConnectionProfile blankHost() {
        ConnectionProfile p = new ConnectionProfile();
        p.setHost("   ");
        return p;
    }

    private static String invalidWithUrl(String url) {
        ConnectionProfile p = new ConnectionProfile();
        p.setUrlOverride(url);
        return p.validationError();
    }

    private static void section(Reporter reporter, String title) {
        reporter.accept(true, "\u2014\u2014 " + title + " \u2014\u2014");
    }

    private static void check(Reporter reporter, String what, boolean ok) {
        report(reporter, ok, what);
    }

    private static void report(Reporter reporter, boolean ok, String what) {
        reporter.accept(ok, what);
    }

    /** Command line entry point: prints a report and exits non-zero when anything fails. */
    public static void main(String[] args) {
        Results results = new Results();
        Reporter console = (ok, what) -> {
            results.record(ok, what);
            System.out.println(ok ? "   [ok]   " + what : "   [FAIL] " + what);
        };
        run(Settings.fromSystemProperties(), console);
        System.out.println();
        System.out.println("========================================");
        System.out.println("PASSED: " + results.passed() + "   FAILED: " + results.failures().size());
        System.out.println("========================================");
        if (!results.failures().isEmpty()) {
            System.exit(1);
        }
    }
}
