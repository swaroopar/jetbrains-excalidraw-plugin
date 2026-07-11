# NFR2 — README documents the manual build steps & architecture

The README documents the manual build steps so a developer can produce the same
plugin distribution zip locally as the CI workflow (C2) — without inspecting the
workflow file. The documentation covers prerequisites (JDK / JetBrains Runtime,
Gradle), the build command (`./gradlew buildPlugin`), where the artifact is
emitted (`build/distributions/`), and how to install it into the IDE ("Install
Plugin from Disk") for manual testing.

The README also contains a **high-level architecture diagram** (rendered inline,
e.g. Mermaid) that shows **every component** involved in opening and rendering a
diagram, so a new contributor understands the system end-to-end at a glance. The
diagram must include, at minimum: the Kotlin plugin modules (file types + icon,
editor + provider, JCEF host + custom-scheme handler + its app-init registrar,
JS↔Kotlin bridge, persistence, theme, export, settings); the relevant IntelliJ
Platform services (VFS/Document, LafManager, Notifications, Configurable,
PersistentStateComponent); the JetBrains Runtime's JCEF/Chromium browser; the
bundled web app served over the `excalidraw://` scheme (`index.html`, `bundle.js`,
`bundle.css`, the `@excalidraw/excalidraw` React app, React/react-dom); and the
build/CI pipeline (the `@excalidraw/excalidraw` npm package, webpack, Gradle +
IntelliJ Platform Gradle Plugin, GitHub Actions). It should also convey the main
flows (scene load/save via VFS, the JS bridge, theme, export) and be accompanied
by a short component legend/table.

## Acceptance Criteria

- AC-NFR2-01: The README contains a build section listing prerequisites and the
  build command (`./gradlew buildPlugin`).
- AC-NFR2-02: Following the documented steps produces the same plugin
  distribution zip that the CI workflow (C2) uploads.
- AC-NFR2-03: The README explains how to install the built zip into the IDE
  ("Install Plugin from Disk") for manual testing.
- AC-NFR2-04: The README contains a high-level architecture diagram (rendered
  inline) plus a component legend that together cover every runtime component
  (plugin modules, IntelliJ Platform services, JBR/JCEF, the bundled
  `@excalidraw/excalidraw` web app and the `excalidraw://` scheme, the JS↔Kotlin
  bridge, persistence/VFS) and the build/CI pipeline.
