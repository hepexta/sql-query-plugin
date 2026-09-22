# Changelog

All notable changes to this plugin are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin version is the
`pluginVersion` property in `gradle.properties`.

## [1.2.0] - 2026-09-22

Retargeted at the current IntelliJ IDEA release. No behaviour changes for the user.

### Changed

- **Target IDE raised to IntelliJ IDEA 2026.2.3** (build 262), from 2025.3 / build 253.
- **Build now requires JDK 25.** IDEA 2026.2 bundles JBR 25 and its platform classes are Java 25
  bytecode (class file major version 69), so compiling against it with JDK 21 fails with
  `bad class file: ... class file has wrong version 69.0, should be 65.0`. Added the
  `javaToolchain` property to `gradle.properties` so the toolchain version is explicit rather
  than hard-coded in the build script.
- **`pluginSinceBuild` raised to `262`**, so the plugin installs on 2026.2 and later. There is
  still no `until-build`, so it keeps installing after future IDE upgrades.

### Fixed

- **Identifier quoting now handles reserved keywords.** A relation named `user`, `select` or
  `table` previously produced `select * from public.user limit 20`, which reads the *session
  user* rather than that table. Generated SQL now quotes such names
  (`select * from public."user" limit 20;`). Clause-introducing words such as `order` and
  `limit` are quoted as well, because although PostgreSQL classifies them as non-reserved they
  are syntactically special inside a `FROM` clause. Ordinary names are still left bare, so the
  common case stays readable.
- Corrected the plugin `LICENSE`/`NOTICE` file references and dropped the bundled PostgreSQL JDBC
  driver from the repository. It is BSD-2-Clause licensed by the PostgreSQL Global Development
  Group, which would require reproducing its notice in binary distributions; the plugin resolves
  a driver at runtime instead and never redistributes one.

### Added

- `verification` section in `README.md` recording what each test suite covers and the known
  upstream issue below.
- A compatibility table in `README.md` for raising or lowering the target IDE, including the
  matching `pluginSinceBuild` and `javaToolchain` values.

### Known issues

- **`ToolWindowBootTest` fails on 2026.2, for an upstream reason.** The unified IntelliJ IDEA
  distribution registers an Ultimate post-startup activity whose class is obfuscated, and
  instantiating it throws:

  ```
  PluginException: Cannot create class Z.Z.Z.Z.Z [Plugin: com.intellij.modules.ultimate]
  Caused by: Cannot find suitable constructor for class Z.Z.Z.Z.Z
  ```

  The JetBrains test framework promotes any logged error to a test failure, so that single
  platform defect fails every UI test. It does not affect the real IDE: booting 2026.2.3 with
  `gradlew runIde` loads the plugin with no exception and the defective class never appears.
  `CoreIntegrationTest` is unaffected. Run only that suite with
  `gradlew test --tests "*CoreIntegrationTest*"`.

## [1.1.0] - 2026-09-19

### Added

- **Database structure browser** as a left-hand panel in the tool window: a lazily loaded tree of
  schemas containing Tables, Views, Materialized Views, Functions, Procedures, Triggers,
  Sequences, Types and Extensions. Expanding a relation shows its columns (type, NOT NULL,
  default) and indexes, with primary keys marked.
- **Double-click a table or view to run `select * from <schema>.<table> limit 20;`** and see the
  result grid. Other object kinds generate a useful starting statement instead: a function
  becomes `select * from fn() limit 20`, a sequence shows its current value, a trigger shows its
  definition, and a schema lists its columns.
- Filter box, Refresh, Collapse All and Preview Selected actions for the browser.

### Changed

- The tool window is now two columns: structure browser on the left, editor over results on the
  right, with a draggable divider.

## [1.0.0] - 2026-09-19

First complete version.

### Added

- Connection profiles with host, port, database, user, password, SSL mode, an optional JDBC URL
  override and an optional driver jar path. Passwords are stored in the IDE credential store,
  never in plain text on disk.
- SQL editor with `Ctrl+Enter` to run the statement at the caret and `Ctrl+Shift+Enter` to run
  the selection or the whole script.
- Statement splitter that understands single quotes with `''` escapes, double-quoted identifiers,
  `$tag$…$tag$` bodies, `--` line comments and nested `/* /* */ */` comments.
- Results in a sortable grid, one tab per statement, with rows returned, rows affected and
  elapsed time. SQL `NULL` renders as an italic grey `NULL`, distinct from an empty string, and
  binary values render as hex.
- Row fetch limit and per-statement timeout, to keep an accidental `SELECT *` on a large table
  from freezing the IDE.
- Auto-commit toggle plus explicit Commit and Rollback, and a Stop button that cancels the
  running statement.
- Copy cell, row, or the whole result set with headers as TSV.
- Confirmation prompt before `DROP`, `TRUNCATE` or `DELETE FROM` statements.
- PostgreSQL JDBC driver resolution: a configured jar first, then the driver bundled with the
  IDE's Database Tools plugin, with a documented fallback if neither is present.
