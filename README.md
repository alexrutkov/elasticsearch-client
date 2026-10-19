# Elasticsearch Client for IntelliJ IDEA

Open-source Elasticsearch/OpenSearch plugin for IntelliJ IDEA, built from scratch with the
IntelliJ Platform SDK. It is not affiliated with Elastic or with any commercial plugin.

## Features (M1 + M2 + M3)

- Multiple connections: name, URL, Basic / API key / Bearer auth, custom HTTP headers.
- Secrets are stored in the IDE credential store (`PasswordSafe`), not in XML.
- SSL verification switch for self-signed clusters.
- Configurable connect/request timeouts.
- "Test Connection" button with cluster name, version and health.
- Tool window with lazy tree: consoles, requests, queries, indices, aliases, data streams, nodes.
- Cluster health indicator (green / yellow / red).
- REST console: method, path, headers, body, formatted JSON response, Ctrl+Enter.
- Save consoles, save query bodies, request history (last 100 per connection).
- Data browser in an editor tab: double-click an index or alias, or use the context menu.
  Columns are built from the mapping, so every field is visible; arrays are rendered
  as `[a, b]`, nested fields as `parent.child`, with pagination, filter and sorting.
- Index context menu: data browser, REST console, info, stats, new index, import,
  copy/reindex, manage aliases, refresh, flush, force merge, update mapping, close, delete.
- Alias context menu: data browser, REST console, alias info, delete alias.
- Add, edit, remove and refresh connections; settings page under
  `Settings | Tools | Elasticsearch Client`.

## Requirements

- IntelliJ IDEA 2026.2 (build 262) or newer.
- JDK 21 to run Gradle (JetBrains Runtime from IDEA 2025.2 works).
- IntelliJ Platform Gradle Plugin 2.10.5, Gradle 8.14.x.

Gradle 9 is not used here on purpose: in the development sandbox it fails on the project
directory writability check. On a regular desktop it is fine to upgrade later.

## Build

`gradle.properties` points `idePath` to the local IDEA installation:

```properties
idePath=C:/Users/rutkovskiy_ayu/AppData/Local/Programs/IntelliJ IDEA
```

Build the installable zip:

```powershell
.\gradlew.bat buildPlugin
```

The result is `build/distributions/elasticsearch-client-0.4.0.zip`.
Install it with `Settings | Plugins | Install Plugin from Disk...`.

Run a sandbox IDE with the plugin:

```powershell
.\gradlew.bat runIde
```

Run unit tests:

```powershell
.\gradlew.bat test
```

If the build runs inside the DSH sandbox, use the verified workarounds:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\jbr'
$env:GRADLE_USER_HOME = '<workspace>\.gradle-home'
gradle.bat buildPlugin --no-daemon --no-watch-fs
```

## Project layout

```
src/main/kotlin/dev/esplugin/
├── client/      EsClient, response models, auth headers, TLS
├── settings/    connection state, PasswordSafe, dialog, settings page
├── ui/          tool window, tree nodes/renderer, actions
└── icons/       plugin icon
```

## Roadmap

- M2: REST console with request/response panels and history.
- M3: data browser (documents table, CRUD, export/import).
- M4: custom CA/client certificates, SSH tunnel, AWS SigV4.
- M5: KQL / Painless / Query DSL completion.

## License

Apache License 2.0. See [LICENSE](LICENSE).
