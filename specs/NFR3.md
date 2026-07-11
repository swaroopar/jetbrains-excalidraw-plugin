# NFR3 — Build, IDE compatibility & plugin-load contract

Hard-won build/packaging and platform-registration constraints. Each one, when
missing, broke the plugin in a real IDE (build failed, plugin refused to install, or
the editor failed to instantiate) even though unit tests passed. They MUST be
preserved on any re-build.

## Build

- NFR3-BUILD-1: `./gradlew buildPlugin` succeeds with **no extra flags**. The
  `buildSearchableOptions` task is disabled (`intellijPlatform { buildSearchableOptions = false }`)
  because it launches a headless IDE that fails on recent JetBrains Runtimes
  (`ClassNotFoundException: com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider`).
  It is optional (settings-search pre-indexing) and not required for a functional plugin.

## Compatibility

- NFR3-COMPAT-1: The plugin declares an **open `until-build`** (no upper bound;
  `ideaVersion.untilBuild = provider { null }`, no `pluginUntilBuild`). A fixed
  `until-build` (e.g. `251.*`) makes the IDE reject the plugin as incompatible after an
  IDE upgrade (observed on WebStorm build 261). `since-build` remains 241.

## Plugin-load contract (IntelliJ Platform)

- NFR3-LOAD-1: Every class registered in `plugin.xml` is instantiable by the platform.
  In particular a `FileEditorProvider` must have a **no-arg constructor**; any
  extra (e.g. test-injection) constructor must be annotated
  `@com.intellij.serviceContainer.NonInjectable` so the platform's constructor injection
  does not fail with "getComponentAdapterOfType is used to get kotlin.jvm.functions.Function0".
- NFR3-LOAD-2: A `FileEditorProvider` whose `getPolicy()` returns a hiding policy
  (`HIDE_DEFAULT_EDITOR` / `HIDE_OTHER_EDITORS`) MUST implement
  `com.intellij.openapi.project.DumbAware` — otherwise the platform refuses to create the
  editor ("HIDE_DEFAULT_EDITOR is supported only for DumbAware providers").

## JCEF web-app loading

- NFR3-JCEF-1: The `excalidraw://` custom scheme handler factory is registered **exactly
  once, at application startup, before JBCefApp initialises** (via an
  `ApplicationInitializedListener`). Registering it per-editor throws
  `IllegalStateException: JBCefApp has already been initialized!` and the editor cannot open.
- NFR3-JCEF-2: The custom scheme is registered as **non-local** (`isLocal = false`,
  standard + secure) so the served page has a normal web origin; a local (file-like)
  scheme yields an opaque origin under which same-origin sub-resources are refused.
- NFR3-JCEF-3: Scheme-handler responses set the **bare** MIME type on the CEF response
  (e.g. `text/html`, not `text/html; charset=utf-8`); a charset-suffixed value makes CEF
  render HTML as plain text. Charset is conveyed via a separate `Content-Type` header.
- NFR3-JCEF-4: The Content-Security-Policy (both the index.html `<meta>` and the
  response header) explicitly allows the `excalidraw:` scheme (and `data:`/`blob:`) in
  `script-src`, `style-src`, `img-src`, `font-src`, `connect-src`, `worker-src`, because
  CSP `'self'` does not reliably match a custom-scheme origin. It MUST NOT permit any
  `http(s)` origin, so there is no network egress (preserves NFR1 privacy).

## Acceptance Criteria

- AC-NFR3-01: A clean checkout builds with `./gradlew buildPlugin` (no extra flags) and
  produces the distribution zip.
- AC-NFR3-02: The packaged `plugin.xml` declares `since-build` with no `until-build`
  upper bound; the plugin installs on the current IDE build and is not rejected as
  incompatible.
- AC-NFR3-03: The plugin installs from disk and loads without `PluginException`; opening
  an accepted file creates the Excalidraw editor (no "Cannot create editor by provider"
  / no `NonInjectable` / `DumbAware` / `JBCefApp has already been initialized` errors).
- AC-NFR3-04: With the editor open, the CSP permits the bundled sub-resources to load
  while permitting no `http(s)` origin (verifiable: no `http://`/`https://` in the CSP).
