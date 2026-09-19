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
        counter.check(true, "===== StatementSafety =====");
        safetyChecks(counter);
        counter.check(true, "===== SqlValueFormatter =====");
        formatterChecks(counter);
        counter.check(true, "===== SqlIdentifiers =====");
        identifierChecks(counter);
        counter.check(true, "===== Connection =====");
        databaseChecks(profile, settings.password(), counter);
        return counter.count;
    }

    /** Identifier quoting and the statements the structure browser generates. */
    private static void identifierChecks(Reporter reporter) {
        check(reporter, "plain identifier not quoted", "agent".equals(SqlIdentifiers.maybeQuote("agent")));
        check(reporter, "underscore not quoted", "agent_tool".equals(SqlIdentifiers.maybeQuote("agent_tool")));
        check(reporter, "digits after first char not quoted", "t1".equals(SqlIdentifiers.maybeQuote("t1")));
        check(reporter, "upper case is quoted", "\"Agent\"".equals(SqlIdentifiers.maybeQuote("Agent")));
        check(reporter, "leading digit is quoted", "\"1t\"".equals(SqlIdentifiers.maybeQuote("1t")));
        check(reporter, "space is quoted", "\"my table\"".equals(SqlIdentifiers.maybeQuote("my table")));
        check(reporter, "embedded quote is doubled", "\"a\"\"b\"".equals(SqlIdentifiers.maybeQuote("a\"b")));
        // Reserved keywords would change meaning unquoted: "select * from user" reads the
        // session user, not a table named user.
        check(reporter, "reserved word 'user' is quoted",
                "\"user\"".equals(SqlIdentifiers.maybeQuote("user")));
        check(reporter, "reserved word 'select' is quoted",
                "\"select\"".equals(SqlIdentifiers.maybeQuote("select")));
        check(reporter, "reserved word 'table' is quoted",
                "\"table\"".equals(SqlIdentifiers.maybeQuote("table")));
        // Clause-introducing words are quoted too: "select * from order limit 20" is legal
        // PostgreSQL but reads as a syntax error, so the generator stays unambiguous.
        check(reporter, "clause word 'order' is quoted",
                "\"order\"".equals(SqlIdentifiers.maybeQuote("order")));
        check(reporter, "clause word 'limit' is quoted",
                "\"limit\"".equals(SqlIdentifiers.maybeQuote("limit")));
        // Ordinary names must stay readable - this is the common case by far.
        check(reporter, "ordinary name stays bare", "agent_event".equals(SqlIdentifiers.maybeQuote("agent_event")));
        check(reporter, "preview for an ordinary table is clean",
                "select * from public.agent limit 20;".equals(SqlIdentifiers.selectPreview("public", "agent")));
        check(reporter, "preview quotes a reserved table name",
                "select * from public.\"user\" limit 20;".equals(SqlIdentifiers.selectPreview("public", "user")));
        check(reporter, "qualified name",
                "public.agent".equals(SqlIdentifiers.qualified("public", "agent")));
        check(reporter, "qualified name quotes parts independently",
                "public.\"MyTable\"".equals(SqlIdentifiers.qualified("public", "MyTable")));
        check(reporter, "qualified name quotes a reserved part",
                "public.\"user\"".equals(SqlIdentifiers.qualified("public", "user")));

        check(reporter, "preview SQL shape",
                "select * from public.agent limit 20;".equals(SqlIdentifiers.selectPreview("public", "agent")));
        check(reporter, "preview SQL honours a custom limit",
                "select * from public.agent limit 5;".equals(SqlIdentifiers.selectPreview("public", "agent", 5)));
        check(reporter, "preview SQL clamps a silly limit",
                "select * from public.agent limit 1;".equals(SqlIdentifiers.selectPreview("public", "agent", 0)));
        check(reporter, "routine skeleton",
                "select * from public.f() limit 20;".equals(SqlIdentifiers.callRoutine("public", "f")));
        check(reporter, "sequence value SQL",
                "select * from public.s;".equals(SqlIdentifiers.sequenceValue("public", "s")));
        check(reporter, "schema overview quotes the literal",
                SqlIdentifiers.schemaOverview("o'brien").contains("'o''brien'"));
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

    /**
     * Guards the confirmation prompt for destructive statements. This is the only thing standing
     * between a misplaced caret and a dropped table, and both known ways around it were silent:
     * a destructive statement after a harmless one, and a nested block comment.
     */
    private static void safetyChecks(Reporter reporter) {
        check(reporter, "plain select is not destructive", !StatementSafety.isDestructive("select 1"));
        check(reporter, "insert is not destructive",
                !StatementSafety.isDestructive("insert into t values (1)"));
        check(reporter, "a table merely named like one is not destructive",
                !StatementSafety.isDestructive("select * from dropped_items"));
        check(reporter, "comment-only script has nothing to run",
                !StatementSafety.isDestructive("-- nothing here\n"));
        check(reporter, "create table is not destructive",
                !StatementSafety.isDestructive("create table t(id int)"));

        check(reporter, "drop table is destructive", StatementSafety.isDestructive("drop table t"));
        check(reporter, "truncate is destructive", StatementSafety.isDestructive("truncate t"));
        check(reporter, "delete from is destructive",
                StatementSafety.isDestructive("delete from t where id = 1"));
        check(reporter, "alter table drop column is destructive",
                StatementSafety.isDestructive("alter table t drop column c"));
        check(reporter, "keyword case does not matter", StatementSafety.isDestructive("DROP TABLE t;"));
        check(reporter, "leading line comment is skipped",
                StatementSafety.isDestructive("-- maintenance\n/* also a comment */ drop table t"));
        check(reporter, "leading block comment is skipped",
                StatementSafety.isDestructive("/* maintenance */ drop table t"));

        // Both of these used to return false: the first because only the start of the whole
        // script was examined, the second because the first "*/" was taken as the end of the
        // comment. Each would have run a DROP without asking.
        check(reporter, "destructive statement after a harmless one is found",
                StatementSafety.isDestructive("select 1; drop table t;"));
        check(reporter, "nested block comment cannot hide the statement",
                StatementSafety.isDestructive("/* outer /* inner */ still comment */ drop table t"));
    }

    private static void formatterChecks(Reporter reporter) {        check(reporter, "null renders as NULL", "NULL".equals(SqlValueFormatter.display(null)));
        check(reporter, "empty string stays empty", "".equals(SqlValueFormatter.display("")));
        check(reporter, "integer", "42".equals(SqlValueFormatter.display(42)));
        check(reporter, "newline escaped", "a\\nb".equals(SqlValueFormatter.display("a\nb")));
        check(reporter, "binary as hex", "\\x0aff".equals(SqlValueFormatter.display(new byte[]{10, (byte) 0xff})));
        check(reporter, "array in braces",
                "{1, NULL, 3}".equals(SqlValueFormatter.display(new Object[]{1, null, 3})));
    }

    /**
     * The schema browser's read layer, exercised against the live server: schemas, relations,
     * columns, indexes, routines, triggers, sequences, types and extensions.
     */
    private static void structureChecks(PostgresSession session, Reporter reporter) {
        try {
            List<String> schemaNames = session.schemaNames();
            check(reporter, "structure: public schema listed", schemaNames.contains("public"));
            check(reporter, "structure: system schemas hidden",
                    schemaNames.stream().noneMatch(s -> s.startsWith("pg_")
                            || "information_schema".equals(s)));

            List<DbStructureReader.Relation> relations = session.relationsOf("public");
            check(reporter, "structure: relations found (" + relations.size() + ")", !relations.isEmpty());
            check(reporter, "structure: the agent table is present",
                    relations.stream().anyMatch(r -> "agent".equals(r.name()) && r.isTableLike()));
            check(reporter, "structure: every relation has a kind",
                    relations.stream().allMatch(r -> r.kind() != null && !r.kind().isBlank()));
            check(reporter, "structure: qualified name is schema-qualified",
                    relations.stream().allMatch(r -> r.qualifiedName().startsWith("public.")));

            // Columns of a known table from the allagents schema.
            List<DbStructureReader.Column> columns = session.columnsOf("public", "agent");
            check(reporter, "structure: agent has columns (" + columns.size() + ")", !columns.isEmpty());
            check(reporter, "structure: every column has a type",
                    columns.stream().allMatch(c -> c.dataType() != null && !c.dataType().isBlank()));
            check(reporter, "structure: an id column exists",
                    columns.stream().anyMatch(c -> "id".equals(c.name())));
            check(reporter, "structure: not-null flags are read",
                    columns.stream().anyMatch(c -> !c.nullable()));

            // Indexes: the schema uses primary keys everywhere.
            List<DbStructureReader.Index> indexes = session.indexesOf("public", "agent");
            check(reporter, "structure: indexes read for agent", !indexes.isEmpty());
            check(reporter, "structure: a primary key is reported",
                    indexes.stream().anyMatch(DbStructureReader.Index::primary));
            check(reporter, "structure: index definitions are present",
                    indexes.stream().allMatch(i -> i.definition() != null && !i.definition().isBlank()));

            // Routines, triggers, sequences and types must all be queryable without error.
            List<DbStructureReader.Routine> functions = session.routinesOf("public", false);
            check(reporter, "structure: function query works (" + functions.size() + " found)", true);
            check(reporter, "structure: function signatures render",
                    functions.isEmpty() || functions.stream().noneMatch(f -> f.signature().isBlank()));
            List<DbStructureReader.Routine> procedures = session.routinesOf("public", true);
            check(reporter, "structure: procedures are separated from functions",
                    procedures.stream().allMatch(DbStructureReader.Routine::isProcedure));

            List<DbStructureReader.Trigger> triggers = session.triggersOf("public");
            check(reporter, "structure: trigger query works (" + triggers.size() + " found)", true);
            check(reporter, "structure: triggers carry a timing and event",
                    triggers.stream().noneMatch(t -> t.timing().isBlank() || t.event().isBlank()));

            List<DbStructureReader.Sequence> sequences = session.sequencesOf("public");
            check(reporter, "structure: sequence query works (" + sequences.size() + " found)", true);

            List<DbStructureReader.TypeInfo> types = session.typesOf("public");
            check(reporter, "structure: type query works (" + types.size() + " found)", true);

            List<DbStructureReader.Extension> extensions = session.extensions();
            check(reporter, "structure: extensions listed (" + extensions.size() + ")",
                    extensions.stream().anyMatch(e -> "plpgsql".equals(e.name())));

            // A generated preview must actually run: this is the double-click behaviour.
            if (!relations.isEmpty() && relations.get(0).isTableLike()) {
                String preview = SqlIdentifiers.selectPreview("public", relations.get(0).name());
                StatementResult previewResult = SqlRunner.execute(session.requireConnection(), preview, 20, 30);
                check(reporter, "structure: generated preview runs and is capped at 20 rows",
                        previewResult instanceof StatementResult.QueryResult q && q.rowCount() <= 20);
                check(reporter, "structure: generated preview is marked truncated when capped",
                        previewResult instanceof StatementResult.QueryResult q
                                && (!q.truncated() || q.rowCount() == 20));
            }
        } catch (Exception e) {
            check(reporter, "structure browsing failed: " + e.getMessage(), false);
        }
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
            structureChecks(session, reporter);
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
