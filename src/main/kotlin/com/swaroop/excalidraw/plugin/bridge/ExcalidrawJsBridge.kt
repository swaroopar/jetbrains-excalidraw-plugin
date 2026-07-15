package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.swaroop.excalidraw.plugin.export.ExportMessage
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * ExcalidrawJsBridge — typed bidirectional channel between Kotlin and the
 * Excalidraw web app running in JCEF.
 *
 * Architecture decisions enforced here:
 *
 *   AD-02 (No eval without handler): All Kotlin→JS communication is done via a
 *   single, named window function (`window.__excalidrawLoadScene__`). The function
 *   is called with a single JSON string argument produced by
 *   [BridgeMessage.LoadScene.toJson]. The JSON string is embedded in the JS call
 *   as a properly escaped string literal — no raw [eval] is used.
 *
 *   A03 (Injection prevention): The JSON payload is serialised by [Gson] and then
 *   the resulting string is escaped by [Gson.toJson] *again* to produce a safe
 *   JS string literal — no runtime string concatenation of untrusted data.
 *
 *   Disposal: The bridge implements [Disposable]. After [dispose] is called,
 *   [loadScene] is a no-op and [simulateSceneChange] is a no-op. In production
 *   mode, the [JBCefJSQuery] is also disposed so that the message router handler
 *   is removed.
 *
 * Threading: [loadScene] may be called on the EDT; it delegates the actual
 * browser call to [injector] which, in production, calls
 * `CefBrowser.executeJavaScript` (thread-safe).
 *
 * Scene-change back-channel (task-03-003):
 *   Incoming JS→Kotlin JSON payloads are untrusted. They are deserialised
 *   exclusively via [BridgeMessage.fromJson] (Gson-typed, no eval). Malformed or
 *   unknown payloads are logged at WARN level and silently discarded — no crash,
 *   no silent swallow without log.
 */
class ExcalidrawJsBridge private constructor(
    private val injector: (String) -> Unit,
    private val readyHandler: (String) -> Unit,
    private val jsQueryDispose: (() -> Unit)?,
    private val sceneChangeHandler: (SceneChangeMessage) -> Unit = {},
    /**
     * Produces the JS expression that sends a payload string to the Kotlin
     * [JBCefJSQuery] handler.  In production this is [JBCefJSQuery.inject];
     * in test mode it is a no-op stub (the return channel is never exercised
     * via real JCEF in unit tests — [simulateSceneChange] is used instead).
     *
     * The lambda receives a JS identifier name (a local variable that holds
     * the payload at call time) and returns a JS expression string.
     *
     * A03: the expression is produced by [JBCefJSQuery.inject], not by
     * string-concatenating user/file data.  The payload identifier is a safe
     * JS variable reference, not untrusted content.
     */
    private val jsQueryInject: (String) -> String = { "" },
    /**
     * Produces the JS expression that performs a request/response round-trip to the
     * clipboard [JBCefJSQuery] (see [installClipboardBridge]). Unlike [jsQueryInject]
     * (fire-and-forget), this uses the three-arg [JBCefJSQuery.inject] so the JS side
     * receives the system-clipboard text back via an onSuccess callback.
     *
     * The lambda receives the JS identifiers holding the request payload and the
     * success/failure callbacks, and returns the native query-call expression.
     * In test mode it is a no-op stub.
     */
    private val clipboardInject: (reqVar: String, okVar: String, errVar: String) -> String = { _, _, _ -> "" }
) : Disposable {

    @Volatile
    private var disposed: Boolean = false

    /**
     * One-shot callback slot for export results (JS→Kotlin, task-06-004). Set by
     * [registerExportResultCallback]; delivered exactly once via [OneShotCallback.deliver].
     */
    private val exportResultCallback = OneShotCallback<ExportMessage.ExportResult>()

    /**
     * One-shot callback slot for PNG extraction results (JS→Kotlin, task-07-003). Set by
     * [registerPngExtractedCallback]; delivered exactly once via [OneShotCallback.deliver].
     */
    private val pngExtractedCallback = OneShotCallback<BridgeMessage.PngExtracted>()

    /**
     * One-shot callback slot for PNG export results (JS→Kotlin, task-07-003). Set by
     * [registerPngExportedCallback]; delivered exactly once via [OneShotCallback.deliver].
     */
    private val pngExportedCallback = OneShotCallback<BridgeMessage.PngExported>()

    /**
     * Persistent (not one-shot) callback for library changes (JS→Kotlin). Set by
     * [registerLibraryChangeCallback]; invoked on every [BridgeMessage.LibraryChange]
     * with the full library items JSON so the host can persist it.
     */
    @Volatile
    internal var libraryChangeCallback: ((String) -> Unit)? = null

    /**
     * Serialises [scene] via [BridgeMessage.LoadScene.toJson] and injects a JS
     * call to `window.__excalidrawLoadScene__` with the resulting JSON as a
     * string literal argument.
     *
     * A03 compliance:
     * - [BridgeMessage.LoadScene.toJson] produces a sanitised JSON string.
     * - [GSON.toJson] encodes that string into an escaped JS string literal
     *   (adds surrounding double-quotes and escapes internal quotes/backslashes).
     * - No `eval()`, no `Function(code)`, no template-literal injection.
     * - The JS fragment is therefore of the form:
     *     `window.__excalidrawLoadScene__("{\"type\":\"loadScene\",...}")`
     *   where the inner payload is a pure JSON string with no executable code.
     */
    fun loadScene(scene: ExcalidrawScene) {
        if (disposed) return
        val json = BridgeMessage.LoadScene(scene).toJson()
        // Encode the JSON string as a safe JS string literal (double-quoted,
        // all inner quotes and backslashes escaped by Gson).  This value is used
        // as the sole argument to the known window function — no eval.
        val jsStringLiteral = GSON.toJson(json)          // e.g. "{\"type\":\"loadScene\",...}"
        val jsCall = "window.__excalidrawLoadScene__($jsStringLiteral);"
        injector(jsCall)
    }

    /**
     * Injects a JS call to `window.__excalidrawSetTheme__` with the theme string
     * encoded as a safe JS string literal via [Gson.toJson].
     *
     * A03 compliance:
     * - [theme] is encoded via [GSON.toJson] to produce a properly escaped
     *   double-quoted JS string literal — no raw string concatenation of
     *   user-controlled data, no eval().
     * - The call is of the form:
     *     `window.__excalidrawSetTheme__("dark");`
     *   where the argument is a pure JSON string literal.
     *
     * After [dispose] this is a no-op.
     *
     * @param theme The Excalidraw theme value — expected to be "dark" or "light".
     */
    fun sendThemeUpdate(theme: String) {
        if (disposed) return
        // Encode theme as a safe JS string literal (adds surrounding quotes and
        // escapes internal quotes/backslashes).  No eval, no concatenation of
        // untrusted data — A03 compliant.
        val jsStringLiteral = GSON.toJson(theme)
        val jsCall = "window.$THEME_FN($jsStringLiteral);"
        injector(jsCall)
    }

    /**
     * Injects a JS call to `window.__excalidrawAddLibrary__` with a JSON array of
     * Excalidraw library items (parsed/normalised by the Kotlin side from a fetched
     * `.excalidrawlib`). The JS side merges them via excalidrawAPI.updateLibrary.
     *
     * A03: [libraryItemsJson] is passed only as a quoted JS string literal (via Gson) —
     * no eval, no concatenation. After [dispose] this is a no-op.
     */
    fun addLibrary(libraryItemsJson: String) {
        if (disposed) return
        val jsStringLiteral = GSON.toJson(libraryItemsJson)
        injector("window.$ADD_LIBRARY_FN($jsStringLiteral);")
    }

    /**
     * Injects a JS call to `window.__excalidrawLoadLibrary__` to REPLACE the editor's
     * library with [libraryItemsJson] (a JSON array of library items). Used at load time
     * to restore the persisted library. A03: passed only as a quoted JS string literal.
     * After [dispose] this is a no-op.
     */
    fun loadLibrary(libraryItemsJson: String) {
        if (disposed) return
        val jsStringLiteral = GSON.toJson(libraryItemsJson)
        injector("window.$LOAD_LIBRARY_FN($jsStringLiteral);")
    }

    /**
     * Registers a persistent callback invoked with the full library items JSON whenever
     * the editor's library changes (add/remove/reorder). Used by the editor to persist
     * the library. After [dispose] this is a no-op.
     */
    fun registerLibraryChangeCallback(cb: (String) -> Unit) {
        if (disposed) return
        libraryChangeCallback = cb
    }

    /**
     * Registers a one-shot callback for the next JS→Kotlin export result.
     *
     * The [callback] is stored in the [exportResultCallback] slot.  When the
     * web app posts an exportResult message (via [simulateExportResult] in tests
     * or the real [JBCefJSQuery] handler in production), the callback is invoked
     * on the EDT via [ApplicationManager.getApplication().invokeLater] and then
     * the slot is immediately cleared to null — guaranteeing at-most-once delivery.
     *
     * After [dispose] this is a no-op (the bridge will never dispatch any more
     * messages, and the callback will never be called).
     *
     * @param cb Lambda invoked with the [ExportMessage.ExportResult] produced by
     *   the Excalidraw web app after an export operation.
     */
    fun registerExportResultCallback(cb: (ExportMessage.ExportResult) -> Unit) {
        if (disposed) return
        exportResultCallback.set(cb)
    }

    /**
     * Injects a JS call to `window.__excalidrawExport__` that triggers an export
     * in the Excalidraw web app.
     *
     * A03 compliance:
     * - [format] is encoded via [GSON.toJson] to produce a properly escaped
     *   double-quoted JS string literal — no raw string concatenation of
     *   user-controlled data.
     * - [scale] is a [Double] (a numeric primitive) — safe for direct embedding.
     * - No [eval], no [Function] constructor, no template-literal injection.
     * - The call is of the form:
     *     `window.__excalidrawExport__("svg", 1.0);`
     *
     * After [dispose] this is a no-op.
     *
     * @param format The export format — expected to be `"svg"` or `"png"`.
     * @param scale  The device-pixel scale factor for PNG exports.
     */
    fun requestExport(format: String, scale: Double) {
        if (disposed) return
        // A03: format encoded via Gson.toJson produces a safe JSON string literal
        // (surrounding double-quotes + internal escaping). scale is a numeric
        // primitive — no injection risk.
        val formatLiteral = GSON.toJson(format)
        val jsCall = "window.$EXPORT_FN($formatLiteral, $scale);"
        injector(jsCall)
    }

    /**
     * Registers a one-shot callback for the next JS→Kotlin PNG extraction result.
     *
     * The [cb] is stored in the [pngExtractedCallback] slot. When the web app posts
     * a pngExtracted message (via [simulatePngExtracted] in tests or the real
     * [JBCefJSQuery] handler in production), the callback is invoked on the EDT via
     * [ApplicationManager.getApplication().invokeLater] and then the slot is cleared
     * to null — guaranteeing at-most-once delivery.
     *
     * After [dispose] this is a no-op.
     *
     * @param cb Lambda invoked with the [BridgeMessage.PngExtracted] payload.
     */
    fun registerPngExtractedCallback(cb: (BridgeMessage.PngExtracted) -> Unit) {
        if (disposed) return
        pngExtractedCallback.set(cb)
    }

    /**
     * Registers a one-shot callback for the next JS→Kotlin PNG export result.
     *
     * The [cb] is stored in the [pngExportedCallback] slot. When the web app posts
     * a pngExported message (via [simulatePngExported] in tests or the real
     * [JBCefJSQuery] handler in production), the callback is invoked on the EDT via
     * [ApplicationManager.getApplication().invokeLater] and then the slot is cleared
     * to null — guaranteeing at-most-once delivery.
     *
     * After [dispose] this is a no-op.
     *
     * @param cb Lambda invoked with the [BridgeMessage.PngExported] payload.
     */
    fun registerPngExportedCallback(cb: (BridgeMessage.PngExported) -> Unit) {
        if (disposed) return
        pngExportedCallback.set(cb)
    }

    /**
     * Injects a JS call to `window.__excalidrawLoadPng__` that triggers PNG
     * scene extraction in the Excalidraw web app.
     *
     * A03 compliance:
     * - [dataUrl] is encoded via [GSON.toJson] to produce a properly escaped
     *   double-quoted JS string literal — no raw string concatenation of
     *   user-controlled data, no eval().
     * - The call is of the form:
     *     `window.__excalidrawLoadPng__("data:image/png;base64,...");`
     *
     * After [dispose] this is a no-op.
     *
     * @param dataUrl The data URL of the PNG file to extract the scene from.
     */
    fun requestPngExtract(dataUrl: String) {
        if (disposed) return
        // A03: dataUrl encoded via Gson.toJson produces a safe JSON string literal
        // (surrounding double-quotes + internal escaping). No eval, no raw concatenation.
        val jsStringLiteral = GSON.toJson(dataUrl)
        val jsCall = "window.$LOAD_PNG_FN($jsStringLiteral);"
        injector(jsCall)
    }

    /**
     * Injects a JS call to `window.__excalidrawExportPng__` that triggers PNG
     * re-embedding in the Excalidraw web app.
     *
     * A03 compliance:
     * - [sceneJson] is encoded via [GSON.toJson] to produce a properly escaped
     *   double-quoted JS string literal — no raw string concatenation of
     *   user-controlled data, no eval().
     * - The call is of the form:
     *     `window.__excalidrawExportPng__("{\"type\":\"excalidraw\",...}");`
     *
     * After [dispose] this is a no-op.
     *
     * @param sceneJson The Excalidraw scene JSON to embed in the PNG.
     */
    fun requestPngExport(sceneJson: String) {
        if (disposed) return
        // A03: sceneJson encoded via Gson.toJson produces a safe JSON string literal
        // (surrounding double-quotes + internal escaping). No eval, no raw concatenation.
        val jsStringLiteral = GSON.toJson(sceneJson)
        val jsCall = "window.$EXPORT_PNG_FN($jsStringLiteral);"
        injector(jsCall)
    }

    /**
     * Installs the JS→Kotlin return channel in the loaded page by injecting a
     * JS script that defines `window.__excalidrawPostToKotlin__`.
     *
     * The installed function is the stable, named counterpart of
     * `window.__excalidrawLoadScene__` (the Kotlin→JS channel):
     *
     * ```js
     * window.__excalidrawPostToKotlin__ = function(payload) {
     *   <jsQuery.inject(payload)>
     * };
     * ```
     *
     * where `<jsQuery.inject(payload)>` is the expression produced by
     * [JBCefJSQuery.inject] — a safe JCEF native query call that routes
     * `payload` to the registered [addHandler] on the Kotlin side.
     *
     * A03 compliance:
     * - The payload argument is a JS variable reference, not concatenated
     *   user data.  No `eval()`, no `Function(code)`.
     * - [jsQueryInject] is [JBCefJSQuery.inject] in production — it builds
     *   the native `window.cefQuery_*` call internally; we do not replicate
     *   that logic here.
     *
     * Must be called after the page has loaded (i.e. from the loadEnd
     * callback) so that the injected function is available before the first
     * [onChange] event fires from the Excalidraw React component.
     *
     * After [dispose] this is a no-op.
     */
    fun installReturnChannel() {
        if (disposed) return
        // jsQueryInject("p") produces the JBCefJSQuery native call expression
        // that sends the value of the JS variable `p` to Kotlin's addHandler.
        // We wrap it in a named window function so the JS bundle can call it
        // via the stable name __excalidrawPostToKotlin__.
        val injectExpr = jsQueryInject("p")
        val js = "window.__excalidrawPostToKotlin__ = function(p) { $injectExpr };"
        injector(js)
    }

    /**
     * Installs a clipboard bridge in the loaded page: routes the browser's async
     * Clipboard API through the JVM system clipboard.
     *
     * Why this is needed: Excalidraw copy/paste uses `navigator.clipboard.readText()`
     * / `writeText()` (and `read()`/`write()`). In embedded Chromium (JCEF) the async
     * clipboard READ is gated behind a `clipboard-read` permission that has no UI and is
     * auto-denied, so pasting fails with Excalidraw's "Couldn't paste (couldn't read from
     * system clipboard)". JCEF exposes no permission-prompt hook to grant it. Instead we
     * replace `navigator.clipboard` with a shim whose `readText`/`writeText`/`read`/`write`
     * round-trip to Kotlin via a [JBCefJSQuery], and Kotlin reads/writes the real OS
     * clipboard (see [readSystemClipboardText] / [writeSystemClipboardText]).
     *
     * `read()` returns the clipboard text wrapped as a `text/plain` `ClipboardItem`, and
     * `write()` bridges the `text/plain` item — matching Excalidraw's element/text copy &
     * paste (its serialised elements travel as `text/plain` JSON). Image (PNG) clipboard
     * items are out of scope here and fall through to a no-op.
     *
     * Injected once per load (guarded by `__excalidrawClipboardInstalled__`), from the
     * loadEnd callback like [installReturnChannel]; Excalidraw re-reads `navigator.clipboard`
     * on every call, so replacing it after mount takes effect for all later copy/paste.
     *
     * A03: the request payload is a JSON string; the injected code contains no eval / no
     * concatenation of untrusted data — only the fixed shim and the [JBCefJSQuery] call.
     * After [dispose] this is a no-op.
     */
    fun installClipboardBridge() {
        if (disposed) return
        // The three-arg inject wires request → onSuccess(text) / onFailure(code,msg).
        val queryCall = clipboardInject("requestJson", "onOk", "onErr")
        val js = """
            (function () {
              if (window.__excalidrawClipboardInstalled__) { return; }
              window.__excalidrawClipboardInstalled__ = true;
              window.$CLIPBOARD_FN = function (requestJson, onOk, onErr) { $queryCall };
              function bridge(reqObj) {
                return new Promise(function (resolve, reject) {
                  if (typeof window.$CLIPBOARD_FN !== "function") { reject(new Error("clipboard bridge unavailable")); return; }
                  window.$CLIPBOARD_FN(JSON.stringify(reqObj),
                    function (resp) { resolve(typeof resp === "string" ? resp : ""); },
                    function (code, msg) { reject(new Error(msg || "clipboard bridge error")); });
                });
              }
              var shim = {
                readText: function () { return bridge({ op: "readText" }); },
                writeText: function (text) { return bridge({ op: "writeText", text: (text == null ? "" : String(text)) }).then(function () { return undefined; }); },
                read: function () {
                  return bridge({ op: "readText" }).then(function (text) {
                    if (!text) { return []; }
                    return [new window.ClipboardItem({ "text/plain": new Blob([text], { type: "text/plain" }) })];
                  });
                },
                write: function (items) {
                  try {
                    if (items && items.length) {
                      var item = items[0];
                      if (item && item.types && item.types.indexOf("text/plain") !== -1 && typeof item.getType === "function") {
                        return item.getType("text/plain")
                          .then(function (b) { return b.text(); })
                          .then(function (t) { return bridge({ op: "writeText", text: String(t) }); })
                          .then(function () { return undefined; });
                      }
                    }
                  } catch (e) { /* fall through to no-op */ }
                  return Promise.resolve();
                }
              };
              try {
                Object.defineProperty(navigator, "clipboard", { value: shim, configurable: true });
              } catch (e) {
                try {
                  navigator.clipboard.readText = shim.readText;
                  navigator.clipboard.writeText = shim.writeText;
                  navigator.clipboard.read = shim.read;
                  navigator.clipboard.write = shim.write;
                } catch (e2) { /* clipboard not patchable — leave native (paste stays broken) */ }
              }
            })();
        """.trimIndent()
        injector(js)
    }

    /**
     * Invokes [readyHandler] with [signal].  Called in production by the
     * [JBCefJSQuery] message-router handler when the JS side calls the query
     * function.  Exposed internally so that unit tests can simulate the signal
     * without a real JCEF runtime.
     *
     * Test-only: do not call from production code paths.
     */
    internal fun simulateReadySignal(signal: String) {
        readyHandler(signal)
    }

    /**
     * Simulates receiving a JS→Kotlin message from the Excalidraw app (test-only).
     * Parses [json] and routes it through [dispatch] (same routing as production).
     *
     * After [dispose], this is a no-op.
     *
     * Security (A09): malformed or unknown payloads are logged at WARN level.
     * No crash, no silent swallow.
     *
     * Test-only: do not call from production code paths (production wires the
     * JBCefJSQuery handler instead).
     */
    internal fun simulateSceneChange(json: String) {
        if (disposed) return
        val message = BridgeMessage.fromJson(json)
        dispatch(json, message)
    }

    /**
     * Simulates receiving an export result from the Excalidraw app (test-only).
     * Parses [json] and routes it through [dispatch] (same routing as production).
     *
     * After [dispose], this is a no-op.
     *
     * Security (A09): malformed or unknown payloads are logged at WARN level.
     * No crash, no silent swallow.
     *
     * Test-only: do not call from production code paths (production wires the
     * [JBCefJSQuery] handler instead).
     */
    internal fun simulateExportResult(json: String) {
        if (disposed) return
        val message = BridgeMessage.fromJson(json)
        dispatch(json, message)
    }

    /**
     * Simulates receiving a PNG extraction result from the Excalidraw app (test-only).
     * Parses [json] and routes it through [dispatch] (same routing as production).
     *
     * After [dispose], this is a no-op.
     *
     * Security (A09): malformed or unknown payloads are logged at WARN level.
     * No crash, no silent swallow.
     *
     * Test-only: do not call from production code paths.
     */
    internal fun simulatePngExtracted(json: String) {
        if (disposed) return
        val message = BridgeMessage.fromJson(json)
        dispatch(json, message)
    }

    /**
     * Simulates receiving a PNG export result from the Excalidraw app (test-only).
     * Parses [json] and routes it through [dispatch] (same routing as production).
     *
     * After [dispose], this is a no-op.
     *
     * Security (A09): malformed or unknown payloads are logged at WARN level.
     * No crash, no silent swallow.
     *
     * Test-only: do not call from production code paths.
     */
    internal fun simulatePngExported(json: String) {
        if (disposed) return
        val message = BridgeMessage.fromJson(json)
        dispatch(json, message)
    }

    /**
     * Disposes the bridge: marks it as disposed and releases the [JBCefJSQuery]
     * (if in production mode) so that the message router handler is cleaned up.
     * After disposal, [loadScene] and [simulateSceneChange] are no-ops.
     */
    override fun dispose() {
        disposed = true
        exportResultCallback.clear()
        pngExtractedCallback.clear()
        pngExportedCallback.clear()
        jsQueryDispose?.invoke()
    }

    /**
     * Dispatches a parsed JS→Kotlin message to its handler based on type.
     * Centralizes all message routing logic so production and test paths
     * use identical dispatch behavior.
     *
     * Routes via [BridgeMessage] type:
     * - [BridgeMessage.SceneChange] → [sceneChangeHandler] on EDT
     * - [BridgeMessage.ExportResult] → [exportResultCallback]
     * - [BridgeMessage.PngExtracted] → [pngExtractedCallback]
     * - [BridgeMessage.PngExported] → [pngExportedCallback]
     * - [BridgeMessage.LibraryChange] → [libraryChangeCallback] on EDT
     * - `null` (unrecognised) → [readyHandler] with raw JSON, logged at WARN
     *
     * Note: [BridgeMessage.fromJson] only ever returns the JS→Kotlin subtypes
     * above or `null` — it never produces [BridgeMessage.Ready] (nor the
     * Kotlin→JS [BridgeMessage.LoadScene]). In practice only the `null` case
     * reaches [readyHandler]; the [BridgeMessage.Ready] and `else` branches are
     * retained defensively for exhaustiveness and future message types.
     *
     * Malformed (null) payloads are logged at WARN level.
     *
     * @param rawJson The original JSON string (used to pass to [readyHandler]
     *   for backwards compatibility with the single-handler design).
     * @param parsed The [BridgeMessage] parsed from [rawJson], or null if
     *   malformed or unrecognised.
     */
    private fun dispatch(rawJson: String, parsed: BridgeMessage?) {
        when (parsed) {
            is BridgeMessage.SceneChange -> {
                ApplicationManager.getApplication()?.invokeLater {
                    sceneChangeHandler(parsed.payload)
                } ?: sceneChangeHandler(parsed.payload)   // fallback for unit-test context without Application
            }
            is BridgeMessage.ExportResult -> {
                exportResultCallback.deliver(parsed.payload)
            }
            is BridgeMessage.PngExtracted -> {
                pngExtractedCallback.deliver(parsed)
            }
            is BridgeMessage.PngExported -> {
                pngExportedCallback.deliver(parsed)
            }
            is BridgeMessage.LibraryChange -> {
                val cb = libraryChangeCallback
                if (cb != null) {
                    ApplicationManager.getApplication()?.invokeLater {
                        cb(parsed.libraryItemsJson)
                    } ?: cb(parsed.libraryItemsJson)   // fallback for unit-test context without Application
                }
            }
            is BridgeMessage.Ready, null -> {
                readyHandler(rawJson)
                if (parsed == null) {
                    LOG.warn("ExcalidrawJsBridge: unrecognised JS→Kotlin payload (discarded)")
                }
            }
            // Defensive fallback: [BridgeMessage.fromJson] never produces the
            // Kotlin→JS-only subtypes (e.g. [BridgeMessage.LoadScene]), so this
            // branch is unreachable in practice. It satisfies the compiler's
            // exhaustiveness check and guards against future message types.
            else -> readyHandler(rawJson)
        }
    }

    companion object {

        private val LOG: Logger = Logger.getInstance(ExcalidrawJsBridge::class.java)

        /**
         * The stable JS window-function name installed by [installReturnChannel].
         *
         * Symmetrical to `window.__excalidrawLoadScene__` (the Kotlin→JS channel).
         * The JS bundle (index.jsx) calls this function to post messages to Kotlin
         * via the JBCefJSQuery back-channel.
         *
         * This constant is the single source of truth for the name — referenced by
         * [installReturnChannel] when generating the JS definition and testable in
         * [ExcalidrawJsBridgeTest] without hard-coding the string.
         */
        const val RETURN_CHANNEL_FN: String = "__excalidrawPostToKotlin__"

        /**
         * The stable JS window-function name called by [sendThemeUpdate].
         *
         * Single source of truth for the Kotlin→JS theme-update channel.
         * The JS bundle (index.jsx) must expose this function on `window` so the
         * Kotlin side can set the Excalidraw theme after the page has loaded.
         *
         * Referenced by [sendThemeUpdate] when generating the JS call, and
         * independently testable via [ExcalidrawJsBridgeThemeTest.THEME_FN constant].
         */
        const val THEME_FN: String = "__excalidrawSetTheme__"

        /**
         * The stable JS window-function name called by [requestExport].
         *
         * Single source of truth for the Kotlin→JS export-trigger channel.
         * The JS bundle (index.jsx) must expose this function on `window` so the
         * Kotlin side can trigger an export operation with the given format and scale.
         *
         * Referenced by [requestExport] when generating the JS call, and
         * independently testable via [ExcalidrawJsBridgeExportTest.EXPORT_FN constant].
         */
        const val EXPORT_FN: String = "__excalidrawExport__"

        /**
         * The stable JS window-function name called by [requestPngExtract].
         *
         * Single source of truth for the Kotlin→JS PNG-extraction channel.
         * The JS bundle (index.jsx) must expose this function on `window` so the
         * Kotlin side can trigger a PNG scene extraction with the given data URL.
         *
         * Referenced by [requestPngExtract] when generating the JS call, and
         * independently testable via [ExcalidrawJsBridgePngTest.LOAD_PNG_FN constant].
         */
        const val LOAD_PNG_FN: String = "__excalidrawLoadPng__"

        /**
         * The stable JS window-function name called by [requestPngExport].
         *
         * Single source of truth for the Kotlin→JS PNG-export channel.
         * The JS bundle (index.jsx) must expose this function on `window` so the
         * Kotlin side can trigger a PNG re-embed operation with the given scene JSON.
         *
         * Referenced by [requestPngExport] when generating the JS call, and
         * independently testable via [ExcalidrawJsBridgePngTest.EXPORT_PNG_FN constant].
         */
        const val EXPORT_PNG_FN: String = "__excalidrawExportPng__"

        /**
         * Stable JS window-function name called by [addLibrary] to merge fetched
         * library items into the editor's library via excalidrawAPI.updateLibrary.
         */
        const val ADD_LIBRARY_FN: String = "__excalidrawAddLibrary__"

        /**
         * Stable JS window-function name called by [loadLibrary] to replace the editor's
         * library with the persisted items at load time.
         */
        const val LOAD_LIBRARY_FN: String = "__excalidrawLoadLibrary__"

        /**
         * Stable JS window-function name installed by [installClipboardBridge]; the
         * navigator.clipboard shim calls it to round-trip a clipboard request to Kotlin.
         */
        const val CLIPBOARD_FN: String = "__excalidrawClipboard__"

        /**
         * Handles a clipboard round-trip request from the JS shim installed by
         * [installClipboardBridge]. Runs on a JCEF query thread and returns synchronously.
         *
         * Request shape: `{"op":"readText"}` or `{"op":"writeText","text":"..."}`.
         * Returns the clipboard text (readText) or an empty string (writeText / on any
         * failure) — a benign empty result is preferred to failing the JS promise, which
         * would surface as an Excalidraw paste error even for an empty clipboard.
         */
        internal fun handleClipboardRequest(request: String): JBCefJSQuery.Response {
            return try {
                val obj = JsonParser.parseString(request)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return JBCefJSQuery.Response("")
                when (obj.get("op")?.asString) {
                    "readText" -> JBCefJSQuery.Response(readSystemClipboardText())
                    "writeText" -> {
                        writeSystemClipboardText(obj.get("text")?.asString ?: "")
                        JBCefJSQuery.Response("")
                    }
                    else -> JBCefJSQuery.Response("")
                }
            } catch (e: Exception) {
                LOG.warn("ExcalidrawJsBridge: clipboard request failed", e)
                JBCefJSQuery.Response("")
            }
        }

        /**
         * Reads the OS clipboard's plain-text contents, or "" when it holds no text
         * (e.g. an image only) or is momentarily locked. The clipboard is a shared OS
         * resource; a brief retry rides out a transient lock held by another app.
         */
        private fun readSystemClipboardText(): String {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            repeat(CLIPBOARD_RETRIES) {
                try {
                    return clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
                } catch (_: IllegalStateException) {
                    sleepQuietly()          // clipboard busy — retry
                } catch (_: Exception) {
                    return ""               // no string flavor / IO — treat as empty
                }
            }
            return ""
        }

        /** Writes [text] to the OS clipboard, retrying briefly through a transient lock. */
        private fun writeSystemClipboardText(text: String) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            repeat(CLIPBOARD_RETRIES) {
                try {
                    clipboard.setContents(StringSelection(text), null)
                    return
                } catch (_: IllegalStateException) {
                    sleepQuietly()
                }
            }
        }

        private const val CLIPBOARD_RETRIES: Int = 3

        private fun sleepQuietly() {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /**
         * Shared Gson instance — thread-safe for serialisation (Gson is immutable
         * after construction).  Used to encode the JSON payload into a JS string
         * literal: [Gson.toJson] with a [String] argument produces a properly
         * double-quote-escaped JSON string including surrounding quotes — safe for
         * direct embedding in a JS call.
         *
         * A03: serialisation is the only transformation applied; no string
         * concatenation of user/file data occurs.
         */
        private val GSON: Gson = Gson()

        /**
         * Production factory: creates a bridge backed by a real [JBCefBrowser].
         *
         * A [JBCefJSQuery] is created for the JS→Kotlin return channel (Ready and
         * SceneChange signals). The query injects a named function into each loaded
         * page via its [JBCefJSQuery.inject] mechanism; the web app calls that
         * function to signal readiness or to post scene-change events.
         *
         * Incoming payloads are untrusted and dispatched via [BridgeMessage.fromJson].
         * Malformed payloads are logged at WARN; no crash, no silent swallow.
         *
         * @param browser The [JBCefBrowser] instance that owns the JCEF client.
         * @param readyHandler Lambda invoked when the web app sends the Ready signal.
         * @param sceneChangeHandler Lambda invoked (on EDT via invokeLater) when the
         *   web app posts a sceneChange event.
         */
        fun create(
            browser: JBCefBrowser,
            readyHandler: (String) -> Unit = {},
            sceneChangeHandler: (SceneChangeMessage) -> Unit = {}
        ): ExcalidrawJsBridge {
            val jsQuery = JBCefJSQuery.create(browser)
            // Separate query for the clipboard request/response round-trip (its handler
            // returns a Response with the clipboard text; the main jsQuery is fire-and-forget).
            val clipboardQuery = JBCefJSQuery.create(browser)
            clipboardQuery.addHandler { request -> handleClipboardRequest(request) }

            val injector: (String) -> Unit = { jsCode ->
                // A03: jsCode is produced exclusively by [loadScene] via Gson
                // serialisation — it never contains untrusted/user data in executable form.
                browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url ?: "", 0)
            }

            val bridge = ExcalidrawJsBridge(
                injector = injector,
                readyHandler = readyHandler,
                jsQueryDispose = {
                    jsQuery.dispose()
                    clipboardQuery.dispose()
                },
                sceneChangeHandler = sceneChangeHandler,
                // A03: jsQuery.inject(payloadVar) produces the safe native
                // JCEF query call expression — no user data concatenation.
                jsQueryInject = { payloadVar -> jsQuery.inject(payloadVar) },
                // Three-arg inject: request payload + onSuccess/onFailure callbacks, so the
                // clipboard text flows back to the JS shim. A03: same safe native call.
                clipboardInject = { req, ok, err -> clipboardQuery.inject(req, ok, err) }
            )

            // Register the JS→Kotlin handler.  The handler receives the raw string
            // sent from JS via the query function.  A03: we do not execute the
            // received string as code — we only parse it via Gson.
            jsQuery.addHandler { message ->
                val parsed = BridgeMessage.fromJson(message)
                bridge.dispatch(message, parsed)
                null // null = no synchronous response required
            }

            return bridge
        }

        /**
         * Test factory: creates a bridge that calls [injector] for every
         * JS injection instead of talking to a real JCEF browser.
         *
         * In test mode the return channel is exercised via [simulateSceneChange]
         * rather than through a real JBCefJSQuery, so [jsQueryInject] defaults
         * to a stub that produces a recognisable placeholder expression — enough
         * for tests to assert that [installReturnChannel] was called and produced
         * a JS snippet containing [RETURN_CHANNEL_FN].
         *
         * @param injector Receives the full JS string that would be executed.
         * @param readyHandler Optional lambda for [simulateReadySignal] tests.
         * @param sceneChangeHandler Optional lambda for [simulateSceneChange] tests.
         */
        fun createForTest(
            injector: (String) -> Unit,
            readyHandler: (String) -> Unit = {},
            sceneChangeHandler: (SceneChangeMessage) -> Unit = {}
        ): ExcalidrawJsBridge = ExcalidrawJsBridge(
            injector = injector,
            readyHandler = readyHandler,
            jsQueryDispose = null,
            sceneChangeHandler = sceneChangeHandler,
            // Stub: produces a placeholder expression so tests can assert the
            // installReturnChannel JS output without a real JBCefJSQuery.
            jsQueryInject = { payloadVar -> "window.__jsBridgeStub__($payloadVar)" }
        )
    }
}

/**
 * A callback slot that fires at most once with the next value handed to [deliver],
 * on the EDT (falling back to a direct call outside a live [ApplicationManager]
 * context, e.g. unit tests).
 *
 * Replaces three hand-written copies of "store-once/clear/deliver-on-EDT" that
 * previously lived on [ExcalidrawJsBridge] as separate `@Volatile` fields
 * ([ExcalidrawJsBridge.registerExportResultCallback]/[ExcalidrawJsBridge.registerPngExtractedCallback]/
 * [ExcalidrawJsBridge.registerPngExportedCallback]) with near-identical dispatch code.
 *
 * Volatile so that a [set] from one thread (the registering caller) and a [deliver]
 * from another (the JCEF message-router thread) observe a consistent slot.
 */
private class OneShotCallback<T> {

    @Volatile
    private var callback: ((T) -> Unit)? = null

    /** Stores [cb] to be delivered at most once by the next [deliver] call. */
    fun set(cb: (T) -> Unit) {
        callback = cb
    }

    /**
     * If a callback is registered, clears the slot and invokes it with [value] exactly
     * once — on the EDT via [ApplicationManager.getApplication].invokeLater, or directly
     * if no [com.intellij.openapi.application.Application] is available (unit tests).
     * A no-op if no callback is registered.
     */
    fun deliver(value: T) {
        val cb = callback ?: return
        callback = null
        ApplicationManager.getApplication()?.invokeLater {
            cb(value)
        } ?: cb(value)
    }

    /** Clears the slot without delivering, so a pending callback never fires. */
    fun clear() {
        callback = null
    }
}
