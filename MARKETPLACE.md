# Marketplace upload — Elasticsearch Client 0.6.1

This file is a checklist for publishing the plugin on JetBrains Marketplace.
It is not included in the plugin runtime; keep it in the repository.

## 1. Vendor Details

The first screen is the vendor profile. Suggested values:

| Field | Value | Notes |
|---|---|---|
| Public Vendor Name | `Alexander Rutkovskiy` | Replace with your name or company if you prefer. This is shown on the plugin page. |
| Vendor ID | `rutkovskiy` | Lowercase URL slug used in `plugins.jetbrains.com/vendor/<id>`. Choose carefully; if it is taken, try `rutkovskiy-dev` or `esplugin`. |
| Vendor Status | `Non-trader` | Correct for a free plugin published by an individual, not as a business. Choose `Trader` only if you distribute it as a company or sell related services. |
| Email | your support email | Use an address you can publish for support. The email from `git config user.email` is a starting point; change it if you do not want it public. |
| Show email on Vendor Profile Page | unchecked | Enable only if you want the address visible to everyone. |
| Newsletter | unchecked | Optional. |
| Add more details | optional | A short vendor description and a link to the source repository can be added later. |

Click **Create And Continue**.

## 2. Plugin Details

| Field | Value |
|---|---|
| Plugin name | `Elasticsearch Client` |
| Plugin ID | `dev.esplugin.elasticsearch` |
| Version | `0.6.1` |
| Category | `Developer Tools` |
| Tags | `elasticsearch`, `opensearch`, `rest`, `database`, `search`, `json`, `devops`, `cluster` |
| License | `Apache-2.0` |
| Price | Free |
| Source code URL | `https://github.com/<your-account>/elasticsearch-client` (fill in after creating the repository) |
| Issue tracker URL | `https://github.com/<your-account>/elasticsearch-client/issues` |
| Documentation URL | `https://github.com/<your-account>/elasticsearch-client#readme` |
| Compatibility | IntelliJ IDEA 2026.2 (262) through 2026.3 (263.*) |
| Plugin file | `dist/elasticsearch-client-0.6.1.zip` |

The Plugin ID must stay `dev.esplugin.elasticsearch`: it is the stable identity used by
IDEA for updates. Do not change it between releases.

## 3. Short Description

```text
Elasticsearch and OpenSearch client for IntelliJ IDEA: connections, REST console and data browser.
```

## 4. Full Description

Paste this into the Marketplace description field:

```markdown
**Elasticsearch Client** is a free, open-source plugin for working with Elasticsearch and
OpenSearch clusters directly from IntelliJ IDEA.

### Features

- Multiple connections with Basic, API key and Bearer authentication; secrets are stored in
  the IDE PasswordSafe.
- Cluster tree with indices, aliases, data streams and nodes, plus a health indicator.
- REST console in an editor tab with JSON syntax highlighting, line numbers, auto-indent,
  request history and saved queries.
- Data browser in an editor tab with all mapped fields as columns, nested fields, arrays,
  pagination and a Lucene filter.
- Index and alias actions: info, stats, create, import, copy/reindex, aliases, refresh,
  flush, force merge, mapping, close and delete.
- Settings page under `Settings | Tools | Elasticsearch Client`.

### Requirements

- IntelliJ IDEA 2026.2 or newer.

### Privacy

No telemetry is collected. Connection settings stay local, and passwords are kept in the
IDE credential store. The plugin only sends requests to the cluster URLs that you configure.

Licensed under Apache-2.0. Not affiliated with Elastic or JetBrains.
```

## 5. Screenshots

Capture these on a demo cluster; do not expose real hostnames, credentials, indices or data.
Recommended size: 1280×800 or larger, PNG.

1. `01-cluster-tree.png` — tool window with two connections, indices, aliases and green/yellow health.
2. `02-rest-console.png` — REST console tab with a colored JSON request and a JSON response.
3. `03-data-browser.png` — data browser tab with the filter, pagination and several columns.
4. `04-connection-settings.png` — connection dialog or the settings page.

Add a short caption to each screenshot in Marketplace. The first screenshot is used as the
plugin card image, so make it representative.

### If an existing screenshot is smaller than 1200×760

Do not stretch it to 1200×760: the aspect ratio changes and the UI becomes distorted.
Create a 1200×760 canvas and place the screenshot proportionally in the center instead.

With ImageMagick:

```powershell
magick input.png -resize x700 -background "#1E1F22" -gravity center -extent 1200x760 output.png
```

With the local PowerShell helper (no external tools):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\make-marketplace-screenshot.ps1 -InputPath .\input.png
```

The helper scales the source to fit the canvas height, adds a dark background and a subtle
shadow, and writes a 1200×760 PNG. A native capture at 1200×760 or larger is still sharper.


## 6. Build And Verify

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\jbr'
$env:GRADLE_USER_HOME = '<workspace>\.gradle-home'
gradle.bat clean buildPlugin test --no-daemon --no-console
```

Then check:

```powershell
Get-FileHash dist\elasticsearch-client-0.6.1.zip -Algorithm SHA256
```

Check that the ZIP contains:

```text
elasticsearch-client/
  lib/elasticsearch-client-0.6.1.jar
  lib/*.jar
  META-INF/plugin.xml
```

`plugin.xml` inside the ZIP must contain:

```xml
<id>dev.esplugin.elasticsearch</id>
<version>0.6.1</version>
<vendor>Alexander Rutkovskiy</vendor>
```

Also check `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg` inside the JAR.

Optional but recommended before the first upload:

```powershell
gradle.bat verifyPlugin
```

If the verifier reports `LightVirtualFile` or another `com.intellij.testFramework` class as a
test API, it is a warning, not a Marketplace blocker. It is used only to create in-memory
editor tabs and does not affect runtime behaviour.

## 7. Upload

1. Open <https://plugins.jetbrains.com/author/me> (or **Upload plugin** from the Marketplace).
2. Fill in Vendor Details as in section 1 and continue.
3. Upload `dist/elasticsearch-client-0.6.1.zip`.
4. Fill in Plugin Details as in section 2.
5. Paste the description and add screenshots.
6. Submit for review.
7. After approval, the plugin is available from **Settings | Plugins | Marketplace** and the
   listing URL is shown in the author console.

## 8. Common Review Issues

- **Plugin ID changed**: keep `dev.esplugin.elasticsearch`.
- **Version already used**: Marketplace does not allow re-uploading the same version; bump the
  version in `build.gradle.kts` and `plugin.xml` change notes.
- **Missing license**: select Apache-2.0 and keep the `LICENSE` file in the repository.
- **Screenshots contain secrets**: recapture with a demo cluster.
- **Compatibility too narrow**: the current range is 2026.2–2026.3. Update `sinceBuild` and
  `untilBuild` in `build.gradle.kts` when a new IDEA version is released.
- **Paid/free mismatch**: this plugin is free; do not select a paid plan.
