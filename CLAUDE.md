# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JetBrains IDE plugin (WebStorm/IntelliJ Platform, Kotlin) that provides a custom
editor for `.excalidraw` and `.excalidraw.png` files. It renders the official
`@excalidraw/excalidraw` React app inside the IDE's embedded JCEF (Chromium)
browser, bridged to Kotlin. Everything is bundled and local — no network egress
at runtime.

## Commands

Build the plugin (also builds the web bundle first):

```bash
./gradlew buildPlugin
```

Output: `build/distributions/jetbrains-excalidraw-plugin-<version>.zip`.

Run all tests:

```bash
./gradlew test --no-configuration-cache
```

Run a single test class / method (standard Gradle test filtering):

```bash
./gradlew test --tests "com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridgeTest" --no-configuration-cache
./gradlew test --tests "*ExcalidrawJsBridgeTest.someMethod" --no-configuration-cache
```

Launch a real IDE sandbox with the plugin installed (used by CI's `runide-smoke`
job to catch plugin-descriptor/classloading regressions unit tests can't see):

```bash
./gradlew runIde --no-configuration-cache
```

Build only the web bundle (webpack) without the full Gradle build — useful when
iterating on `excalidraw-bundle/src`:

```bash
cd excalidraw-bundle && npm run build        # production
cd excalidraw-bundle && npm run build:dev    # development
```

`--no-configuration-cache` is used in CI/release because CI passes `-PpluginVersion`
or relies on tag lookups that must not be cached stale; it's not required for
routine local iteration but is safe to always include.

There is no configured linter (no ktlint/detekt); rely on the Kotlin compiler and
tests.

### Version resolution (don't hardcode a version)

The plugin version is resolved in `build.gradle.kts` in this order: (1) an explicit
`-PpluginVersion=<x>` override (what the release workflow passes), (2) otherwise the
latest `v*` git tag bumped to the next patch as `-SNAPSHOT` (e.g. tag `v1.0.3` →
`1.0.4-SNAPSHOT`), (3) otherwise `pluginVersionFallback` in `gradle.properties` (only
used with no git tags available, e.g. a shallow clone). The git tag — not
`gradle.properties` — is the source of truth for released versions; `main` is only
ever updated via PRs, never by a version-bump commit.

### Offline / local IDE builds

Set `localIdePath` in `~/.gradle/gradle.properties` (never commit it) to point at a
local IDE install (e.g. `/Applications/WebStorm.app/Contents` on macOS) to skip
downloading the WebStorm SDK. CI always resolves the SDK remotely via
`platformVersion` in `gradle.properties`.

## Architecture

Full component diagram and table are in `README.md` — read that first for the
complete picture. Summary of the moving parts:

**Kotlin plugin** (`src/main/kotlin/com/swaroop/excalidraw/plugin/`), package-by-layer:

- `filetype/` — registers `.excalidraw` and `.excalidraw.png` (via a `*.excalidraw.png`
  pattern, since the platform only derives the last-dot extension) as custom file types.
- `editor/` — `ExcalidrawFileEditorProvider` (accepts files per extension settings) and
  `ExcalidrawFileEditor`, the `FileEditor` implementation: hosts the browser, loads/saves
  the scene through VFS/Document, debounces autosave via an `Alarm`, wires theme + bridge.
- `jcef/` — `ExcalidrawJcefHost` wraps `JBCefBrowser`; `ExcalidrawSchemeHandlerRegistrar`
  (an `ApplicationInitializedListener`) registers the custom `excalidraw://` scheme handler
  exactly once at app startup, **before** `JBCefApp` initializes — registering it later
  throws `JBCefApp has already been initialized!`; `ExcalidrawSchemeHandler` serves the
  bundled `/webview` assets over that scheme.
- `bridge/` — `ExcalidrawJsBridge`: the Kotlin↔JS bridge over `JBCefJSQuery` plus a fixed
  set of `window.__excalidraw*__` global functions (see below). `BridgeMessage` /
  `SceneChangeMessage` are the Gson-(de)serialized message shapes.
- `persistence/` — `ExcalidrawPersistenceService` reads/writes through the IDE
  Document/VFS (never `java.io.File`); for `.excalidraw.png` it embeds/extracts the scene
  JSON from the PNG. `ExcalidrawScene` / `ExcalidrawSerializer` are the data model and
  canonical `.excalidraw` JSON (de)serialization.
- `theme/` — `ExcalidrawThemeController` subscribes to `LafManager` and pushes the mapped
  light/dark theme into JS; `ThemeMapper` does the IDE-theme → Excalidraw-theme mapping.
- `export/` — `ExcalidrawExporter` plus `ExportSvgAction`/`ExportPngAction`, wired into the
  editor context menu, enabled only when the active editor is an `ExcalidrawFileEditor`.
- `settings/` — `ExcalidrawExtensionSettings` (`PersistentStateComponent`, app-level
  service, self-registers — no `applicationService` XML entry needed) holds the
  configurable list of extensions to handle; `ExcalidrawSettingsConfigurable` is the
  Settings → Tools → Excalidraw panel.
- All extension points are declared in `plugin.xml` — no `EP_NAME.registerExtension()` at
  runtime.

**Bundled web app** (`excalidraw-bundle/src/`, webpack → `src/main/resources/webview/`,
served at runtime via `excalidraw://`):

- `index.jsx` renders `@excalidraw/excalidraw` and defines the JS side of the bridge.
- `webpack.config.js` has a font-embedding plugin: because the `excalidraw://` scheme has
  an opaque origin, URL-based font loads (fetch/`FontFace(url)`/`@font-face url()`) are
  blocked as cross-origin. Fonts are instead embedded as base64 into `fonts-embed.js`
  (`window.__EXCALIDRAW_FONT_BYTES__`), and `index.html` patches `FontFace` to build faces
  from an `ArrayBuffer`. Xiaolai (CJK, ~12 MB) is excluded from the embed; add it back in
  `EXCLUDED_FONT_DIRS` only if CJK canvas text is needed.
- `processResources` in `build.gradle.kts` depends on the `buildWebBundle` Gradle task,
  which runs `npm ci`/`npm install` + `npm run build` in `excalidraw-bundle/` before
  packaging — so a plain `./gradlew buildPlugin` always produces a fresh web bundle.

### The Kotlin↔JS bridge contract

Kotlin → JS is a fixed set of global functions injected/called on the page (constants in
`ExcalidrawJsBridge`, defined in `index.jsx`):

- `__excalidrawLoadScene__` — load a scene JSON into the canvas.
- `__excalidrawSetTheme__` — push the IDE's light/dark theme.
- `__excalidrawExport__(format, scale)` — trigger SVG/PNG export.
- `__excalidrawLoadPng__(dataUrl)` — trigger PNG scene extraction.
- `__excalidrawExportPng__(sceneJson)` — trigger PNG scene embedding.
- `__excalidrawAddLibrary__` / `__excalidrawLoadLibrary__` — merge/replace library items
  (there's no IndexedDB under the opaque `excalidraw://` origin, so the library isn't
  persisted client-side).
- `__excalidrawClipboard__` — clipboard bridging (guarded by
  `__excalidrawClipboardInstalled__` so it's only installed once per load).

JS → Kotlin is a single return channel, `window.__excalidrawPostToKotlin__`, defined by
Kotlin via `JBCefJSQuery` and called by JS with a JSON payload (scene changes, export
results, etc.) that Kotlin dispatches based on message shape.

Ordering matters: these `window.__excalidraw*__` functions only exist after JCEF's
`loadEnd` fires, so anything driving the page (theme pushes, scene loads) must be
sequenced after `loadEnd`, not fired eagerly.

## CI

Three GitHub Actions workflows:

- `build.yml` — every push/PR: runs `./gradlew test` then `./gradlew buildPlugin`, uploads
  the zip as the `plugin-distribution` artifact. Note `buildPlugin` does **not** depend on
  `test`, so the test step is separate and required.
- `runide-smoke.yml` — boots a real headless IDE (Xvfb) with the plugin installed and
  asserts the `excalidraw://` scheme handler registered and no plugin-load problems
  occurred; catches classloading/descriptor regressions unit tests can't see.
- `release.yml` — manual dispatch only, picks major/minor/maintenance, runs tests, computes
  the next version from the latest `v*` tag, builds with `-PpluginVersion`, tags, and
  creates a GitHub Release. Never pushes to `main`.

## Development workflow
- No changes must be pushed to main branch without a PR directly.
- No dependencies must be added until approved by the team.