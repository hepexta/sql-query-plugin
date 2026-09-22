# Simple SQL Query — IntelliJ IDEA plugin

A small, focused SQL console for **PostgreSQL** inside IntelliJ IDEA. It connects over JDBC, runs
the statement under the caret or a whole script, shows results in a sortable grid, and browses the
database structure in a tree — without paying for the Database Tools that Ultimate used to gate.

* Plugin id: `com.sqlquery.simple-sql-query`
* Version: `1.2.0` — see [CHANGELOG.md](CHANGELOG.md)
* Target IDE: **IntelliJ IDEA 2026.2.3** (build 262)
* Since build: `262` (2026.2). No `until-build`, so it keeps working after an IDE upgrade.
* Licence: **MIT** — see [Licence](#licence).

---

## 1. Build

Prerequisite: **JDK 25**. IntelliJ IDEA 2026.2 ships JBR 25 and its platform classes are Java 25
bytecode (class file major version 69), so compiling against it with JDK 21 fails with
`bad class file: ... class file has wrong version 69.0, should be 65.0`. Gradle comes from the
wrapper, and the target IDE is downloaded by the build.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
cd C:\Work\Projects\AI\sql-query-plugin
.\gradlew.bat buildPlugin
```

Result: `build\distributions\sql-query-plugin-1.2.0.zip`

| Task                                             | What it does                                   |
|--------------------------------------------------|------------------------------------------------|
| `.\gradlew.bat buildPlugin`                      | Builds the installable zip                     |
| `.\gradlew.bat test`                             | Runs the headless test suite (see the caveat below) |
| `.\gradlew.bat runIde`                           | Starts a sandbox IDE with the plugin installed |
| `.\gradlew.bat verifyPluginStructure`            | Validates `plugin.xml` and the archive layout  |
| `.\gradlew.bat verifyPluginProjectConfiguration` | Validates the Gradle plugin configuration      |

### Test caveat on 2026.2

`gradlew test` runs two suites. `CoreIntegrationTest` (127 checks against a real PostgreSQL
server) passes. `ToolWindowBootTest` fails on 2026.2 for a reason outside this plugin: the
unified IntelliJ IDEA distribution registers an Ultimate post-startup activity whose class is
obfuscated, and instantiating it throws.

```
PluginException: Cannot create class Z.Z.Z.Z.Z [Plugin: com.intellij.modules.ultimate]
Caused by: Cannot find suitable constructor for class Z.Z.Z.Z.Z
```

The JetBrains test framework turns any logged error into a test failure, so that one platform
defect fails every UI test. It does **not** affect the real IDE — see [Verification](#6-verification).
To run only the unaffected suite:

```powershell
.\gradlew.bat test --tests "*CoreIntegrationTest*"
```

## 2. Install

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Pick `build\distributions\sql-query-plugin-1.2.0.zip`.
3. Restart the IDE.
4. Open **View → Tool Windows → Simple SQL Query**.

## 3. Using it

### Layout

The tool window has two columns. On the **left** is the database structure browser; on the
**right** is the editor over the results. You can drag the divider, and double-click it to
collapse the browser out of the way.

**Right-hand column, top to bottom:**

1. **Connection bar** — profile, Connect/Disconnect, Manage…, Database selector.
2. **Execution toolbar** — Run Statement, Run All, Stop, Auto-commit, Commit, Rollback, Clear.
3. **SQL editor** — editable, with line numbers and soft wraps.
4. **Results tabs** — one tab per statement, each a sortable grid.
5. **Status bar** — connection state on the left, row count on the right.

**Left-hand column — database structure browser.** A tree of schemas and their objects:

```
public
  Tables              agent, agent_capability, agent_event, tool, …
  Views               agent_events_view, …
  Materialized Views
  Functions           fn_name(arg types) → return type
  Procedures
  Triggers            trg_name  · relation · BEFORE INSERT → function
  Sequences           seq_name  · type
  Types               enum / composite / domain / range
  Extensions          plpgsql v1.0
```

* **Double-click a table or view** to run `select * from <schema>.<table> limit 20;` and see the
  grid. This is the main interaction.
* Double-click anything else to get a useful starting statement: a function becomes
  `select * from fn() limit 20`, a sequence shows its current value, a trigger shows its
  definition, and a schema lists its columns.
* Expanding a table shows its **columns** (type, NOT NULL, default) and its **indexes**,
  marking primary keys.
* Children load lazily, so opening a schema with hundreds of tables does not block the UI.
* The **filter box** narrows the loaded objects as you type; **Refresh** re-reads from the
  server, **Collapse All** resets the tree, **Preview Selected** runs the selected object
  without a double-click.

Commit and Rollback are enabled only while auto-commit is off and a session is open.

### Connect

The first run seeds one profile (`Local PostgreSQL`: `localhost:5432/postgres`, user `postgres`).
Click **Manage…** to edit it or add more. **Test Connection** validates the profile and reports
server and driver versions before you save.

| Field             | Notes                                                                                           |
|-------------------|-------------------------------------------------------------------------------------------------|
| Host / Port       | Standard TCP connection details                                                                 |
| Database          | Default database; switch later from the **Database** combo                                      |
| User / Password   | The password goes to the IDE credential store (KeePass by default), never to disk in plain text |
| SSL mode          | `disable`, `allow`, `prefer` (default), `require`, `verify-ca`, `verify-full`                   |
| JDBC URL override | Optional; wins over the individual fields                                                       |
| Driver jar        | Optional — see *JDBC driver* below                                                              |
| Statement timeout | Per-statement timeout in seconds (default 30)                                                   |
| Row fetch limit   | Maximum rows read per result set (default 500)                                                  |

Then press **Connect**.

### Run SQL

| Action                                     | Shortcut           |
|--------------------------------------------|--------------------|
| Run the statement at the caret             | `Ctrl+Enter`       |
| Run the selected text, or the whole script | `Ctrl+Shift+Enter` |

The splitter understands single quotes (with `''` escapes), double-quoted identifiers,
`$tag$…$tag$` bodies, `--` line comments and nested `/* /* */ */` comments, so a semicolon inside
those does not split a statement. Multiple statements produce one results tab each, and the status
bar reports rows returned, rows affected and elapsed time.

### Results

* Sortable grid; click a header to sort, `Ctrl+C` copies the selected cells as TSV.
* Right-click for **Copy Cell**, **Copy Row**, **Copy All (with headers)** — all paste into Excel.
* SQL `NULL` renders as an italic grey `NULL`, distinguishable from an empty string.
* Binary columns render as `\x…` hex.
* Result sets larger than the profile's **Row fetch limit** are flagged as truncated.

### Transactions

**Auto-commit** is on by default. Turn it off from the toolbar to keep one transaction open across
statements, then use **Commit** / **Rollback**. Disconnecting with an open transaction rolls it back.

### Safety

* **Stop** cancels the running statement (it aborts the connection, so the server stops working
  immediately).
* Optional confirmation before `DROP` / `TRUNCATE` / `DELETE FROM`, enabled with
  *"Ask before running DROP / TRUNCATE / DELETE statements"* in the connections dialog.

## 4. JDBC driver

The plugin does **not** bundle a PostgreSQL JDBC driver. It resolves one in this order:

1. the **Driver jar** configured on the connection profile (a jar, or a folder containing jars);
2. the driver bundled with IntelliJ IDEA's Database Tools plugin;
3. any PostgreSQL driver already on the IDE classpath.

If none is found, connecting fails with a message explaining exactly this. The fix is to download
`postgresql-<version>.jar` from <https://jdbc.postgresql.org/download/> and paste its full path
into **Driver jar**, for example:

```
C:\Tools\jdbc\postgresql-42.7.8.jar
```

A copy is already present at `tools\postgresql-42.7.8.jar` in this repository.

## 5. Local development database

Developed and verified against the PostgreSQL instance from
`C:\Work\Projects\AI\agents-infra\database`:

| Setting         | Value               |
|-----------------|---------------------|
| Host / Port     | `localhost:5432`    |
| Database        | `allagents`         |
| User / Password | `agents` / `agents` |
| Server          | PostgreSQL 17.11    |

Start it with `docker compose up -d --build` in that directory, then create a profile with those
values.

## 6. Verification

`.\gradlew.bat test` runs two suites.

**`CoreIntegrationTest` — 127 checks against a real PostgreSQL server.** The JDBC layer
(`com.sqlquery.plugin.db`) contains no IntelliJ APIs precisely so it can be exercised headlessly.
The suite covers statement splitting (comments, nesting, dollar quotes), destructive-statement
detection, value formatting, identifier quoting, connection and schema/database listing,
result-set limits and truncation, DDL/DML row counts, error propagation, transaction
commit/rollback, and the whole structure browser read layer — schemas, relations, columns,
indexes, routines, triggers, sequences, types and extensions — including running a generated
`select ... limit 20` preview and asserting it is capped. It is skipped, not failed, when nothing
is listening on the target port; point it elsewhere with `-Dpg.host`, `-Dpg.port`,
`-Dpg.database`, `-Dpg.user`, `-Dpg.password`.

**`ToolWindowBootTest` — loads the plugin in a real (headless) IDE.** It asserts that the
`toolWindow` extension from `plugin.xml` resolves against this plugin and points at
`SqlQueryToolWindowFactory`, that the factory builds a panel whose UI constructs with a real
preferred size, that the SQL editor is editable (not a read-only viewer), that the connection bar
sits on the NORTH edge, that the Run toolbar is populated with the execution actions, and that the
structure browser is part of the layout and generates the right SQL when a table is activated —
including quoting a table named `user`. It also checks that the `SqlQuerySettings` service
resolves with a seeded profile. Note that the headless IDE registers no tool windows of its own,
so the suite deliberately asserts on the extension point rather than on
`ToolWindowManager.getToolWindow(...)`.


## 7. Project layout

```
src/main/java/com/sqlquery/plugin/
  db/        ConnectionProfile     saved connection definition (no password)
             DbStructureReader     schemas, relations, columns, indexes, routines,
                                   triggers, sequences, types, extensions
             DriverResolver        finds a PostgreSQL JDBC driver
             JarLoader             class loader for a user supplied driver jar
             PostgresSession       one live JDBC session, transactions, cancel, structure
             SqlIdentifiers        identifier quoting + generated SELECT/preview SQL
             SqlRunner             executes a statement, reads results
             SqlSplitter           splits a script into statements
             StatementSafety       destructive-statement detection
             SqlValueFormatter     JDBC value -> display text
             StatementResult       sealed result type (QueryResult | UpdateResult)
  execute/   QueryExecutor          background execution, cancellation, transactions
  settings/  SqlQuerySettings       persisted profiles + credential store access
  ui/        DatabaseStructurePanel database structure browser (tree, filter, double-click)
             SqlQueryPanel             the tool window
             SqlQueryToolWindowFactory  tool window entry point
             ConnectionProfilesDialog   profile editor
             ResultGridModel            table model over a QueryResult
             ResultGridSupport          column sizing, clipboard actions
src/main/resources/META-INF/
  plugin.xml
  simple-sql-query-database.xml   loaded only when Database Tools is present
src/test/java/com/sqlquery/plugin/
  db/CoreIntegrationCheck.java    the 127 checks (also runnable as a main class)
  db/CoreIntegrationTest.java     JUnit wrapper, skips when no server is listening
  ui/ToolWindowBootTest.java      plugin boot, UI construction, browser behaviour

```

## 8. Licence

**MIT License.** Copyright (c) 2026 hepexta. The full text is in [`LICENSE`](LICENSE).

What MIT means in practice:

* Anyone may use, modify, and redistribute this code, including commercially and in closed
  products.
* They **must** keep the copyright notice and the licence text, and they may not claim they
  wrote the original.
* The software comes with no warranty and no liability.

MIT is the usual choice for IntelliJ plugins and is fully compatible with building against the
IntelliJ Platform: the platform SDK is Apache-2.0, which imposes no licence condition on your
own plugin.

### Files that make the licence explicit

| File                     | Purpose                                                                      |
|--------------------------|------------------------------------------------------------------------------|
| `LICENSE`                | The verbatim MIT text                                                        |
| `NOTICE`                 | Attribution, third-party components, trademark disclaimer                    |
| `THIRD-PARTY-NOTICES.md` | Full detail on the platform SDK, the PostgreSQL JDBC driver and icons        |
| `plugin.xml`             | `<vendor url="…">hepexta</vendor>` — the platform has **no** licence element |
| `gradle.properties`      | `pluginLicense=MIT` — documentation only, the build cannot emit it           |
| every `*.java`           | `SPDX-License-Identifier: MIT` header                                        |

**There is no licence field in `plugin.xml` or in the Gradle DSL.** This trips people up, so to
be explicit: the [official plugin configuration
reference](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html) lists the
allowed `<idea-plugin>` children, and `license` is not among them; adding it makes the IDE log
`Unknown element: license` at startup. The IntelliJ Platform Gradle Plugin 2.x
`pluginConfiguration` block has no `license` property either. Declaring the licence is a
**JetBrains Marketplace web-form** field on the plugin's page. In the IDE, attribution comes
from `<vendor>`.

### Before you publish

1. If you publish on **JetBrains Marketplace**, set the plugin's licence to **MIT** in the
   Marketplace upload form — that is the only place it is recorded.
2. **Trademarks.** "IntelliJ", "IntelliJ IDEA" and "JetBrains" are trademarks of JetBrains
   s.r.o. You may say the plugin *is for* IntelliJ IDEA; you may not imply JetBrains endorses
   it, and you must not put "IntelliJ" in the plugin id (Marketplace rejects that).
3. **Ownership.** If any of this was written for an employer or on company time, the employer
   likely owns the copyright, and only they can grant the licence. Check before publishing.
4. Confirm the copyright holder reads the way you want. `hepexta` is currently the holder name
   in `LICENSE`, `NOTICE` and every source header, and the vendor in `plugin.xml`. A legal name
   gives a stronger authorship claim than a handle.

### Why the PostgreSQL JDBC driver is not bundled

The plugin resolves a driver at runtime (see section 4) and never redistributes one. The driver
is BSD-2-Clause licensed by the PostgreSQL Global Development Group, which is permissive but
*does* require reproducing its notice in binary distributions. Not bundling it keeps the
obligations trivial. If you ever do bundle it, the notice text is reproduced in
`THIRD-PARTY-NOTICES.md` for you to carry over.

## 9. Notes on the IntelliJ Platform 2026.2

Five platform facts shape this build, all of them reflected in `build.gradle.kts` and
`gradle.properties`:

* **JDK 25 is required.** IDEA 2026.2 bundles JBR 25 and its platform classes are Java 25
  bytecode (`major version 69`). Building against 2026.2 with JDK 21 fails with
  `class file has wrong version 69.0, should be 65.0`. Hence `javaToolchain=25`.
* **IntelliJ IDEA Community is no longer a target platform.** JetBrains stopped publishing IC
  as a development target in 2025.3; `intellijIdeaCommunity("2026.2.3")` fails with
  `Could not find idea:ideaIC:2026.2.3`. The unified `intellijIdea(...)` dependency is the only
  supported route, and it resolves to the Ultimate distribution. See
  [the JetBrains deprecation thread](https://platform.jetbrains.com/t/intellij-platform-gradle-plugins-intellijideacommunity-deprecation/2709).
* **The platform is modular now.** 2026.2 ships `lib/intellij.platform.*.jar` modules plus
  `platform-loader.jar` and `modules/module-descriptors.dat`; the old monolithic `app.jar` and
  `platform-api.jar` are gone. That is why a stale Gradle cache can produce confusing
  "cannot find symbol" errors for classes like `AnAction`.
* **IntelliJ Platform Gradle Plugin 2.x requires Gradle 9+** (the wrapper is pinned to 9.4.0).
  2.19.0 is the newest release and is what this project uses.
* **`until-build` is deprecated for build 243+** and actively prevents installation on newer
  IDEs, so it is intentionally absent.

### Raising or lowering the target IDE

`platformVersion` and `pluginSinceBuild` in `gradle.properties` control this, and
`javaToolchain` must be at least the JBR the target IDE ships:

| Target IDE | `platformVersion` | `pluginSinceBuild` | `javaToolchain` |
| --- | --- | --- | --- |
| 2026.2 | `2026.2.3` | `262` | `25` |
| 2026.1 | `2026.1.5` | `261` | `25` |
| 2025.3 | `2025.3` | `253` | `21` |

Supporting an older branch means building against it (the platform API is not forward
compatible): set `platformVersion` to that branch, lower `sinceBuild` to match, drop
`javaToolchain` accordingly, and re-run the tests. This plugin uses no 2026.2-only API, so
compiling against an older branch should work unchanged.

