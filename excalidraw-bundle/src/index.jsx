import React from "react";
import { createRoot } from "react-dom/client";
import { Excalidraw, exportToSvg, exportToBlob, loadFromBlob } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";

/**
 * sendToKotlin — posts a JSON payload to the Kotlin side via the stable
 * return-channel function installed by ExcalidrawJsBridge.installReturnChannel().
 *
 * Kotlin defines `window.__excalidrawPostToKotlin__` in the loadEnd callback
 * (before the first onChange can fire). This is the JS→Kotlin counterpart of
 * `window.__excalidrawLoadScene__` (the Kotlin→JS channel).
 *
 * Security (A03): no eval(), no Function(code), no remote fetch. The payload
 * is a plain JSON string produced by JSON.stringify — no executable code.
 *
 * Guard: if the function is not yet available (e.g. during the very first
 * render cycle before the JCEF bridge has run loadEnd), the call is skipped.
 */
function sendToKotlin(payload) {
  if (typeof window.__excalidrawPostToKotlin__ === "function") {
    window.__excalidrawPostToKotlin__(payload);
  }
}

/**
 * renderPngBase64 — rasterises a scene to a Base64-encoded PNG string.
 *
 * The shared render step behind both window.__excalidrawExport__'s "png" branch
 * (export-to-file, current live canvas state) and window.__excalidrawExportPng__
 * (autosave re-embedding, a caller-supplied scene) — the two previously had
 * near-identical exportToBlob + FileReader + strip-data-url-prefix logic, so a
 * bug in that decode/encode path had to be fixed twice.
 *
 * Returns a Promise resolving to the Base64 string (no data-URL prefix).
 */
function renderPngBase64(elements, appState, files, scale) {
  return exportToBlob({
    elements: elements,
    appState: appState,
    files: files,
    mimeType: "image/png",
    scale: scale,
  }).then(function (blob) {
    return new Promise(function (resolve) {
      var reader = new FileReader();
      reader.onload = function (e) {
        var dataUrl = e.target.result;
        resolve(dataUrl.replace(/^data:[^;]+;base64,/, ""));
      };
      reader.readAsDataURL(blob);
    });
  });
}

/**
 * VALID_THEMES — whitelist of accepted theme values (A03: input validation).
 * Only "light" and "dark" are valid Excalidraw theme strings.
 * Any other value passed to window.__excalidrawSetTheme__ is silently ignored.
 */
var VALID_THEMES = ["light", "dark"];

/**
 * initialTheme — reads the `?theme=light|dark` query param that
 * ExcalidrawJcefHost.startUrlWithTheme() appends to the page URL (computed from the
 * IDE's current LookAndFeel at navigation time) and validates it against
 * VALID_THEMES.
 *
 * Fixes the light-then-dark flash: without this, App() always started from
 * React.useState("light") and only switched to "dark" once Kotlin called
 * window.__excalidrawSetTheme__ after loadEnd — visible to the user as a flash of
 * light mode before the correct dark theme applied. Seeding useState from the URL
 * means the very first paint already matches the IDE theme.
 *
 * A03: the raw query value is never trusted directly — it is only used if it is an
 * exact match in the VALID_THEMES whitelist; any other value (or absence) falls
 * back to "light".
 */
function initialTheme() {
  try {
    var params = new URLSearchParams(window.location.search);
    var theme = params.get("theme");
    return VALID_THEMES.indexOf(theme) !== -1 ? theme : "light";
  } catch (e) {
    return "light";
  }
}

/**
 * VALID_EXPORT_FORMATS — whitelist of accepted export format values (A03: input validation).
 * Only "svg" and "png" are supported export formats.
 * Any other value passed to window.__excalidrawExport__ is silently ignored.
 */
var VALID_EXPORT_FORMATS = ["svg", "png"];

/**
 * App — wrapper component that owns the theme state and exposes the
 * window.__excalidrawSetTheme__ and window.__excalidrawExport__ global functions
 * for Kotlin→JS communication.
 *
 * Architecture (arc42-slice task-05-006, task-06-006):
 * - React.useState("light") tracks the current Excalidraw theme.
 * - React.useRef(null) holds the Excalidraw API ref for scene access.
 * - window.__excalidrawSetTheme__(newTheme) is registered once via useEffect
 *   (empty dependency list) so it is available after the first render.
 * - window.__excalidrawExport__(format, scale) is registered once via useEffect
 *   (empty dependency list); uses excalidrawAPIRef to access scene state.
 * - The theme setter validates the input against VALID_THEMES (A03: whitelist).
 * - The export function validates format against VALID_EXPORT_FORMATS (A03: whitelist).
 * - No eval(), no Function(code), no string interpolation for code execution.
 *
 * The functions are available on window before the Kotlin loadEnd callback fires,
 * because React commits and runs effects synchronously relative to the mount.
 */
function App() {
  var themeState = React.useState(initialTheme);
  var theme = themeState[0];
  var setTheme = themeState[1];

  /**
   * excalidrawAPIRef — holds the Excalidraw API instance once the component mounts.
   * Used by window.__excalidrawExport__ to access scene elements, app state, and files.
   */
  var excalidrawAPIRef = React.useRef(null);

  /**
   * themeRef — always mirrors the current `theme` state (IDE-driven, confirmed
   * correct on every render). Read by window.__excalidrawLoadScene__ instead of
   * api.getAppState().theme, because Excalidraw's own internal appState.theme can
   * independently regress to its own default ("light") by the time the scene-load
   * bridge call runs, so it is not a trustworthy source of "the current theme".
   */
  var themeRef = React.useRef(theme);
  themeRef.current = theme;

  React.useEffect(function () {
    /**
     * window.__excalidrawSetTheme__(newTheme) — Kotlin→JS theme-update channel.
     *
     * Security (A03):
     * - newTheme is validated against VALID_THEMES whitelist before use.
     * - setTheme() is a plain React state setter; no code execution.
     * - No eval(), no Function(code), no template-literal string execution.
     */
    window.__excalidrawSetTheme__ = function (newTheme) {
      if (VALID_THEMES.indexOf(newTheme) !== -1) {
        setTheme(newTheme);
      }
    };

    /**
     * window.__excalidrawLoadScene__(json) — Kotlin->JS channel that loads a
     * persisted .excalidraw scene (elements + appState + files) into the canvas.
     *
     * json is `{"type":"loadScene","scene":{...ExcalidrawScene...}}`, produced by
     * BridgeMessage.LoadScene.toJson() and called once per file open, right after
     * installReturnChannel() (loadScene) and before the initial pushCurrentTheme()
     * call.
     *
     * Theme handling: the saved scene's own appState.theme reflects whatever mode
     * the file happened to be in when it was last saved (often "light", since
     * that's Excalidraw's own default) -- it must NOT override the theme the IDE
     * is currently driving. We explicitly stamp the live appState theme (read
     * back from the API right before applying) onto the incoming appState so a
     * scene saved in light mode never regresses an IDE dark-mode session back to
     * light. This is what fixes existing/previously-saved files always opening in
     * light mode regardless of the IDE's theme.
     *
     * Security (A03): json is parsed via JSON.parse (no eval()); malformed input
     * is caught and the canvas is simply left as-is (already blank/new) rather
     * than throwing.
     */
    window.__excalidrawLoadScene__ = function (json) {
      var api = excalidrawAPIRef.current;
      if (!api) {
        return;
      }
      try {
        var payload = JSON.parse(json);
        var scene = (payload && payload.scene) || {};
        var files = scene.files || {};
        var fileArray = Object.keys(files).map(function (k) { return files[k]; });
        if (fileArray.length > 0 && typeof api.addFiles === "function") {
          try { api.addFiles(fileArray); } catch (e) { /* ignore */ }
        }
        var currentTheme = themeRef.current;
        api.updateScene({
          elements: scene.elements || [],
          appState: Object.assign({}, scene.appState || {}, { theme: currentTheme }),
        });
      } catch (e) {
        // Malformed scene JSON: leave the canvas as-is rather than crash.
      }
    };

    /**
     * window.__excalidrawAddLibrary__(itemsJson) — Kotlin->JS channel that merges
     * library items into the editor's library. itemsJson is a JSON array of
     * Excalidraw library items (parsed/normalised by the Kotlin side from the
     * .excalidrawlib the user picked in the in-IDE library browser).
     *
     * Deliberately uses only the existing api.updateLibrary (no extra package
     * imports and no IndexedDB) — the opaque excalidraw:// origin disables
     * IndexedDB, so the library-persistence code paths must be avoided. This is an
     * inert function until invoked, so it cannot affect initial render.
     */
    window.__excalidrawAddLibrary__ = function (itemsJson) {
      var api = excalidrawAPIRef.current;
      if (!api || typeof itemsJson !== "string") {
        return;
      }
      try {
        var items = JSON.parse(itemsJson);
        if (!Array.isArray(items) || items.length === 0) {
          return;
        }
        api.updateLibrary({ libraryItems: items, merge: true, openLibraryMenu: true });
      } catch (e) {
        /* malformed items — ignore */
      }
    };

    /**
     * window.__excalidrawLoadLibrary__(itemsJson) — Kotlin->JS channel that REPLACES the
     * library with the persisted items at load time (merge:false). Same in-memory path as
     * __excalidrawAddLibrary__ (no IndexedDB).
     */
    window.__excalidrawLoadLibrary__ = function (itemsJson) {
      var api = excalidrawAPIRef.current;
      if (!api || typeof itemsJson !== "string") {
        return;
      }
      try {
        var items = JSON.parse(itemsJson);
        if (!Array.isArray(items) || items.length === 0) {
          return;
        }
        api.updateLibrary({ libraryItems: items, merge: false });
      } catch (e) {
        /* malformed items — ignore */
      }
    };

    /**
     * window.__excalidrawExport__(format, scale) — Kotlin→JS export channel.
     *
     * Parameters:
     *   format (string): "svg" or "png" — validated against VALID_EXPORT_FORMATS.
     *   scale  (number): scaling factor for PNG export (e.g. 1.0, 2.0).
     *
     * Behavior:
     * - Guard: returns early if excalidrawAPIRef.current is null (API not yet ready).
     * - Guard: returns early if format is not in VALID_EXPORT_FORMATS (A03 whitelist).
     * - Retrieves scene elements, appState, and files from the Excalidraw API.
     * - For "svg": calls exportToSvg, serializes the SVGSVGElement via XMLSerializer,
     *   then posts the result to Kotlin via sendToKotlin.
     * - For "png": delegates to renderPngBase64 (shared with window.__excalidrawExportPng__)
     *   then posts the resulting Base64 string to Kotlin via sendToKotlin.
     * - Result JSON: { type: "exportResult", format: <format>, data: <serialized> }
     *
     * Security (A03):
     * - format is validated against VALID_EXPORT_FORMATS whitelist; no injection possible.
     * - No eval(), no Function(code), no remote fetch.
     * - JSON.stringify used for safe serialization — no string concatenation for code.
     */
    window.__excalidrawExport__ = function (format, scale) {
      var api = excalidrawAPIRef.current;
      if (!api) {
        return;
      }

      if (VALID_EXPORT_FORMATS.indexOf(format) === -1) {
        return;
      }

      var els = api.getSceneElements();
      var state = api.getAppState();
      var files = api.getFiles();

      if (format === "svg") {
        exportToSvg({ elements: els, appState: state, files: files })
          .then(function (svg) {
            var data = new XMLSerializer().serializeToString(svg);
            sendToKotlin(JSON.stringify({ type: "exportResult", format: "svg", data: data }));
          });
      } else if (format === "png") {
        renderPngBase64(els, state, files, scale).then(function (base64) {
          sendToKotlin(JSON.stringify({ type: "exportResult", format: "png", data: base64 }));
        });
      }
    };

    /**
     * window.__excalidrawLoadPng__(dataUrl) — Kotlin->JS PNG-extraction channel.
     *
     * Receives a data URL (data:image/png;base64,...), converts it to a Blob
     * using atob + Uint8Array (no fetch, no remote request), calls loadFromBlob
     * to extract the embedded Excalidraw scene, updates the canvas, and posts
     * the extracted sceneJson back to Kotlin via sendToKotlin.
     *
     * Security (A03):
     * - No eval(), no Function(code), no fetch to remote URLs.
     * - dataUrl is decoded only via atob() (standard browser API).
     * - Error paths are caught and reported via sendToKotlin, no crash.
     * - No window.__bridge, no string-concatenation for code execution.
     */
    window.__excalidrawLoadPng__ = function (dataUrl) {
      var api = excalidrawAPIRef.current;
      if (!api) {
        return;
      }
      try {
        var base64 = dataUrl.replace(/^data:[^;]+;base64,/, "");
        var binary = atob(base64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
          bytes[i] = binary.charCodeAt(i);
        }
        var blob = new Blob([bytes], { type: "image/png" });
        loadFromBlob(blob, null, null).then(function (data) {
          // Register embedded raster images (image elements reference these by
          // fileId). updateScene does NOT load the files store — addFiles does —
          // so without this, embedded images render as a broken-image placeholder.
          var files = data.files || {};
          var fileArray = Object.keys(files).map(function (k) { return files[k]; });
          if (fileArray.length > 0 && typeof api.addFiles === "function") {
            try { api.addFiles(fileArray); } catch (e) { /* ignore */ }
          }
          api.updateScene({
            elements: data.elements || [],
            appState: data.appState || {},
          });
          sendToKotlin(JSON.stringify({
            type: "pngExtracted",
            sceneJson: JSON.stringify({
              type: "excalidraw",
              elements: data.elements || [],
              appState: data.appState || {},
              files: data.files || {},
              version: 2,
            }),
          }));
        }).catch(function (e) {
          // No embedded Excalidraw scene in this PNG (e.g. a plain raster), or it could
          // not be decoded as one. Open a blank, editable canvas so the user can draw and
          // save — the first save creates a proper .excalidraw.png with an embedded scene.
          // Clearing to empty makes the canvas state match the empty baseline the Kotlin
          // side seeds, so merely opening the file never rewrites it; only a real edit does.
          try { api.updateScene({ elements: [], appState: {} }); } catch (_e) { /* ignore */ }
          sendToKotlin(JSON.stringify({
            type: "pngExtracted",
            error: (e && e.message) ? e.message : "unknown error",
          }));
        });
      } catch (e) {
        sendToKotlin(JSON.stringify({
          type: "pngExtracted",
          error: (e && e.message) ? e.message : "unknown error",
        }));
      }
    };

    /**
     * window.__excalidrawExportPng__(sceneJson) — Kotlin->JS PNG-export channel.
     *
     * Receives a scene JSON string, parses it, and delegates to renderPngBase64
     * (shared with window.__excalidrawExport__'s "png" branch) with
     * exportEmbedScene:true so the PNG carries the embedded scene, then posts the
     * resulting Base64 string back to Kotlin via sendToKotlin.
     *
     * Security (A03):
     * - sceneJson is parsed via JSON.parse in try/catch — no eval(), no code exec.
     * - No window.__bridge, no remote fetch, no string-concatenation for code.
     * - Error paths are caught and reported via sendToKotlin, no crash.
     */
    window.__excalidrawExportPng__ = function (sceneJson) {
      var api = excalidrawAPIRef.current;
      if (!api) {
        return;
      }
      try {
        var scene = JSON.parse(sceneJson);
        // onChange reports deleted elements as { isDeleted: true } rather than removing
        // them from the array, and Excalidraw's exporter renders every element it is
        // handed (it does NOT skip deleted ones). So drop deleted elements here —
        // otherwise "removed" elements keep showing up in the exported PNG.
        var elements = (scene.elements || []).filter(function (el) { return !el.isDeleted; });
        // exportEmbedScene is read from appState (NOT as a top-level option). The scene
        // is embedded into the PNG only when the flag lives here; without it the saved
        // .excalidraw.png carries no scene and re-opens as a blank canvas.
        var appState = Object.assign({}, scene.appState || {}, { exportEmbedScene: true });
        renderPngBase64(elements, appState, scene.files || {}, 1).then(function (base64) {
          sendToKotlin(JSON.stringify({ type: "pngExported", base64Png: base64 }));
        }).catch(function (e) {
          sendToKotlin(JSON.stringify({
            type: "pngExported",
            error: (e && e.message) ? e.message : "unknown error",
          }));
        });
      } catch (e) {
        sendToKotlin(JSON.stringify({
          type: "pngExported",
          error: (e && e.message) ? e.message : "unknown error",
        }));
      }
    };
  }, []);

  return React.createElement(Excalidraw, {
    theme: theme,
    excalidrawAPI: function (api) {
      excalidrawAPIRef.current = api;
    },
    UIOptions: {
      canvasActions: {
        export: false,
        loadScene: false,
      },
    },
    onChange: function (elements, appState) {
      // Self-healing theme guard: Excalidraw's own internal scene
      // initialization can asynchronously reset appState.theme back to its
      // default ("light") shortly after __excalidrawLoadScene__ correctly
      // applies the IDE's theme (a race with Excalidraw's internal restore
      // logic, observed even after explicitly stamping the theme on load).
      // Any onChange callback -- which fires on that internal reset too --
      // is used to immediately re-assert the authoritative theme so the
      // canvas can't get stuck showing the wrong one.
      if (appState && appState.theme !== themeRef.current && excalidrawAPIRef.current) {
        excalidrawAPIRef.current.updateScene({ appState: { theme: themeRef.current } });
      }
      var payload = JSON.stringify({ type: "sceneChange", elements: elements, appState: appState });
      sendToKotlin(payload);
    },
    // Persist the library on every change (add/remove/reorder) so it survives IDE
    // restarts. Kotlin saves the full items and restores them on the next open via
    // __excalidrawLoadLibrary__ (the opaque origin disables Excalidraw's own IndexedDB).
    onLibraryChange: function (libraryItems) {
      sendToKotlin(JSON.stringify({ type: "libraryChange", libraryItems: libraryItems }));
    },
  });
}

var rootElement = document.getElementById("root");
var root = createRoot(rootElement);

root.render(React.createElement(App, null));
