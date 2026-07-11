# NFR1 — Local-only data handling

Drawing data never leaves the user's machine. The plugin makes no network
requests carrying scene content, file content, or telemetry derived from
drawings. The embedded JCEF browser loads only bundled local assets (no remote
URLs, no remotely fetched code); all loading, editing, saving, and export happen
in-process in the IDE and the embedded browser.

## Acceptance Criteria

- AC-NFR1-01: During open, edit, auto-save, and export, the plugin issues no
  network request carrying drawing or file data, and the embedded browser loads
  only bundled local resources.
