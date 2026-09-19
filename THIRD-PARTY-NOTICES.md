# Third-party notices

This project is licensed under the **MIT License** (see `LICENSE`). It builds against, and
interoperates with, the third-party software listed below.

Nothing here is a claim of authorship over any third-party component, and none of these
licenses are changed by this project's license.

---

## 1. IntelliJ Platform SDK

| | |
| --- | --- |
| Copyright | Copyright 2000–2026 JetBrains s.r.o. |
| License | Apache License, Version 2.0 |
| Homepage | <https://www.jetbrains.com/opensource/intellij-platform/> |
| License text | <https://www.apache.org/licenses/LICENSE-2.0> |

**How it is used.** The plugin is compiled against the IntelliJ Platform SDK and runs inside
IntelliJ IDEA, using its public extension points (`com.intellij.toolWindow`), public services
and public APIs. The build downloads the target IDE as a *compile-time* dependency; the
resulting plugin archive contains only this project's own compiled classes — no IntelliJ
Platform code is redistributed.

**What this means for you.** The IntelliJ Platform SDK is licensed under Apache-2.0, which
places no restriction on the license you choose for your own plugin. This project uses MIT.

**Why that matters here.** Because the plugin is compiled against the Apache-2.0 licensed
platform, the plugin itself may be distributed under any license, including a proprietary or
paid one. Choosing MIT means users may reuse the code as long as they keep the notice.
If you ever want to ship a closed-source or paid build, that is permitted — you would
simply stop offering the source under MIT for the new versions.

### Bundled-extension caveat (important)

`plugin.xml` declares an **optional** dependency on the bundled `com.intellij.database`
(Database Tools) plugin. The code only reaches into it reflectively, to ask the IDE for the
PostgreSQL JDBC driver that ships with the IDE:

```java
Class<?> dbUtil = Class.forName("com.intellij.database.util.DbUtil");
Object driver = dbUtil.getMethod("copyViaJdbcDriver", String.class, String.class)
        .invoke(null, DRIVER_CLASS, "42");
```

Notes:

* It is declared `optional="true"`, so the plugin still loads when that plugin is absent.
* No JetBrains code is copied, linked against at build time, or redistributed. The call is
  reflective and best-effort, with a documented fallback (point the connection profile at your
  own `postgresql-<version>.jar`).
* IntelliJ IDEA **Ultimate** bundle terms differ from the Apache-2.0 licensed Community
  sources. If you plan to sell this plugin, get your own legal read on whether calling into
  bundled Ultimate functionality is acceptable for your distribution; for a free or
  open-source plugin using the public API surface reflectively, this is the normal pattern.

This is engineering context, not legal advice.

---

## 2. PostgreSQL JDBC Driver (pgJDBC)

| | |
| --- | --- |
| Copyright | Copyright (c) 1997, PostgreSQL Global Development Group. All rights reserved. |
| License | BSD 2-Clause "Simplified" License |
| Homepage | <https://jdbc.postgresql.org/> |
| License text | <https://jdbc.postgresql.org/license/> |

**How it is used.** Only as an optional JDBC driver at runtime, and as a test-scope dependency
for the headless integration check (`org.postgresql:postgresql:42.7.8`).

**Is it redistributed?**

* **No** — the published plugin archive (`build/distributions/sql-query-plugin-*.zip`) contains
  only this project's own jar. Verified: the archive holds `lib/sql-query-plugin-1.0.0.jar`
  and nothing else.
* **No** — the driver jar is not committed to this repository.
* **Yes, transitively** — if you run `gradlew buildPlugin test`, Gradle downloads the driver
  from Maven Central into your own Gradle cache. That is your copy, obtained directly from the
  copyright holder, and it never enters the project's artifacts.

Because the plugin does not redistribute the driver, its BSD-2-Clause notice obligations are
not triggered by this project. If you ever *do* bundle the driver inside the plugin jar (an
easy change: add it to a `bundled` configuration), the BSD-2-Clause license requires that you
reproduce the copyright notice, the list of conditions and the disclaimer in your
documentation and in any binary distribution. Doing so is permitted — it only requires the
notice, not payment or source disclosure.

Full BSD 2-Clause terms, as published by the pgJDBC project:

```
Copyright (c) 1997, PostgreSQL Global Development Group
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

---

## 3. JetBrains annotations (`org.jetbrains:annotations`)

| | |
| --- | --- |
| Copyright | Copyright 2000–2026 JetBrains s.r.o. |
| License | Apache License, Version 2.0 |
| License text | <https://www.apache.org/licenses/LICENSE-2.0> |

Used as `@NotNull` / `@Nullable` annotations. On the compile classpath these come from the
IntelliJ Platform itself; no separate copy is redistributed. Apache-2.0 permits this freely.

---

## 4. Icons

The tool window icon is referenced as `AllIcons.Database.External`, an icon that is part of
the IntelliJ Platform and is resolved from the running IDE at runtime. **No icon files are
copied into this repository or into the plugin archive.** The Apache-2.0 grant of the
IntelliJ Platform covers this use.

If you later add your own icon files, note that JetBrains permits plugin icons but forbids
using JetBrains trademarks or logos in a way that suggests endorsement.

---

## Summary

| Component              | License      | Redistributed here? | Obligation on you                                     |
|------------------------|--------------|---------------------|-------------------------------------------------------|
| This plugin            | MIT          | Yes (source + jar)  | Keep the copyright notice and license text            |
| IntelliJ Platform SDK  | Apache-2.0   | No                  | None beyond keeping the platform's own notices intact |
| PostgreSQL JDBC driver | BSD-2-Clause | No                  | None; if you bundle it, reproduce its notice          |
| JetBrains annotations  | Apache-2.0   | No                  | None                                                  |
| Platform icons         | Apache-2.0   | No                  | None; do not imply JetBrains endorsement              |
