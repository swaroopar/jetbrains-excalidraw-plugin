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
 * VALID_THEMES — whitelist of accepted theme values (A03: input validation).
 * Only "light" and "dark" are valid Excalidraw theme strings.
 * Any other value passed to window.__excalidrawSetTheme__ is silently ignored.
 */
var VALID_THEMES = ["light", "dark"];

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
  var themeState = React.useState("light");
  var theme = themeState[0];
  var setTheme = themeState[1];

  /**
   * excalidrawAPIRef — holds the Excalidraw API instance once the component mounts.
   * Used by window.__excalidrawExport__ to access scene elements, app state, and files.
   */
  var excalidrawAPIRef = React.useRef(null);

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
     * - For "png": calls exportToBlob, converts the Blob to a data URL via FileReader,
     *   strips the "data:image/png;base64," prefix, then posts the Base64 string
     *   to Kotlin via sendToKotlin.
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
        exportToBlob({
          elements: els,
          appState: state,
          files: files,
          mimeType: "image/png",
          scale: scale,
        }).then(function (blob) {
          var reader = new FileReader();
          reader.onload = function (e) {
            var dataUrl = e.target.result;
            var base64 = dataUrl.replace(/^data:[^;]+;base64,/, "");
            sendToKotlin(JSON.stringify({ type: "exportResult", format: "png", data: base64 }));
          };
          reader.readAsDataURL(blob);
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
          api.updateScene({
            elements: data.elements || [],
            appState: data.appState || {},
            files: data.files || {},
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
     * Receives a scene JSON string, parses it, calls exportToBlob with
     * exportEmbedScene:true to produce a PNG with the scene embedded, reads the
     * result as a data URL via FileReader, strips the data: prefix, and posts the
     * Base64 string back to Kotlin via sendToKotlin.
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
        exportToBlob({
          elements: scene.elements || [],
          appState: scene.appState || {},
          files: scene.files || {},
          mimeType: "image/png",
          exportEmbedScene: true,
        }).then(function (blob) {
          var reader = new FileReader();
          reader.onload = function (e) {
            var dataUrl = e.target.result;
            var base64 = dataUrl.replace(/^data:[^;]+;base64,/, "");
            sendToKotlin(JSON.stringify({ type: "pngExported", base64Png: base64 }));
          };
          reader.readAsDataURL(blob);
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
      var payload = JSON.stringify({ type: "sceneChange", elements: elements, appState: appState });
      sendToKotlin(payload);
    },
  });
}

var rootElement = document.getElementById("root");
var root = createRoot(rootElement);

root.render(React.createElement(App, null));
