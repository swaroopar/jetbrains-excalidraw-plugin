package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.swaroop.excalidraw.plugin.export.ExportMessage
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene

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
    private val jsQueryInject: (String) -> String = { "" }
) : Disposable {

    @Volatile
    private var disposed: Boolean = false

    /**
     * One-shot callback slot for export results (JS→Kotlin, task-06-004).
     *
     * Volatile so that writes from the message-router thread are visible to the
     * EDT thread that invokes [ApplicationManager.getApplication().invokeLater].
     * Set to non-null by [registerExportResultCallback]; cleared to null after
     * the first invocation so the callback fires at most once per registration.
     */
    @Volatile
    private var exportResultCallback: ((ExportMessage.ExportResult) -> Unit)? = null

    /**
     * One-shot callback slot for PNG extraction results (JS→Kotlin, task-07-003).
     *
     * Set to non-null by [registerPngExtractedCallback]; cleared to null after
     * the first invocation (one-shot delivery). After [dispose] this is always null.
     */
    @Volatile
    private var pngExtractedCallback: ((BridgeMessage.PngExtracted) -> Unit)? = null

    /**
     * One-shot callback slot for PNG export results (JS→Kotlin, task-07-003).
     *
     * Set to non-null by [registerPngExportedCallback]; cleared to null after
     * the first invocation (one-shot delivery). After [dispose] this is always null.
     */
    @Volatile
    private var pngExportedCallback: ((BridgeMessage.PngExported) -> Unit)? = null

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
        exportResultCallback = cb
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
        pngExtractedCallback = cb
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
        pngExportedCallback = cb
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
     * Dispatches [json] through [BridgeMessage.fromJson] and, if the result is a
     * [BridgeMessage.SceneChange], invokes [sceneChangeHandler] via
     * `ApplicationManager.getApplication()?.invokeLater` (AD-05: EDT dispatch).
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
        if (message == null) {
            LOG.warn("ExcalidrawJsBridge: received unrecognised or malformed scene-change payload (discarded)")
            return
        }
        when (message) {
            is BridgeMessage.SceneChange -> {
                ApplicationManager.getApplication()?.invokeLater {
                    sceneChangeHandler(message.payload)
                } ?: sceneChangeHandler(message.payload)   // fallback for unit-test context without Application
            }
            else -> {
                LOG.warn("ExcalidrawJsBridge: unexpected message type in scene-change channel: ${message::class.simpleName}")
            }
        }
    }

    /**
     * Dispatches [json] through [BridgeMessage.fromJson] and, if the result is a
     * [BridgeMessage.ExportResult], invokes [exportResultCallback] exactly once
     * on the EDT via [ApplicationManager.getApplication().invokeLater], then
     * clears the callback slot to null (one-shot delivery).
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
        if (message == null) {
            LOG.warn("ExcalidrawJsBridge: received unrecognised or malformed exportResult payload (discarded)")
            return
        }
        when (message) {
            is BridgeMessage.ExportResult -> {
                val cb = exportResultCallback
                if (cb != null) {
                    exportResultCallback = null
                    // AD-05: dispatch to EDT; fall back to direct call in unit-test context.
                    ApplicationManager.getApplication()?.invokeLater {
                        cb(message.payload)
                    } ?: cb(message.payload)
                }
            }
            else -> {
                LOG.warn("ExcalidrawJsBridge: unexpected message type in exportResult channel: ${message::class.simpleName}")
            }
        }
    }

    /**
     * Dispatches [json] through [BridgeMessage.fromJson] and, if the result is a
     * [BridgeMessage.PngExtracted], invokes [pngExtractedCallback] exactly once
     * on the EDT via [ApplicationManager.getApplication().invokeLater], then
     * clears the callback slot to null (one-shot delivery).
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
        if (message == null) {
            LOG.warn("ExcalidrawJsBridge: received unrecognised or malformed pngExtracted payload (discarded)")
            return
        }
        when (message) {
            is BridgeMessage.PngExtracted -> {
                val cb = pngExtractedCallback
                if (cb != null) {
                    pngExtractedCallback = null
                    // AD-05: dispatch to EDT; fall back to direct call in unit-test context.
                    ApplicationManager.getApplication()?.invokeLater {
                        cb(message)
                    } ?: cb(message)
                }
            }
            else -> {
                LOG.warn("ExcalidrawJsBridge: unexpected message type in pngExtracted channel: ${message::class.simpleName}")
            }
        }
    }

    /**
     * Dispatches [json] through [BridgeMessage.fromJson] and, if the result is a
     * [BridgeMessage.PngExported], invokes [pngExportedCallback] exactly once
     * on the EDT via [ApplicationManager.getApplication().invokeLater], then
     * clears the callback slot to null (one-shot delivery).
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
        if (message == null) {
            LOG.warn("ExcalidrawJsBridge: received unrecognised or malformed pngExported payload (discarded)")
            return
        }
        when (message) {
            is BridgeMessage.PngExported -> {
                val cb = pngExportedCallback
                if (cb != null) {
                    pngExportedCallback = null
                    // AD-05: dispatch to EDT; fall back to direct call in unit-test context.
                    ApplicationManager.getApplication()?.invokeLater {
                        cb(message)
                    } ?: cb(message)
                }
            }
            else -> {
                LOG.warn("ExcalidrawJsBridge: unexpected message type in pngExported channel: ${message::class.simpleName}")
            }
        }
    }

    /**
     * Disposes the bridge: marks it as disposed and releases the [JBCefJSQuery]
     * (if in production mode) so that the message router handler is cleaned up.
     * After disposal, [loadScene] and [simulateSceneChange] are no-ops.
     */
    override fun dispose() {
        disposed = true
        exportResultCallback = null
        pngExtractedCallback = null
        pngExportedCallback = null
        jsQueryDispose?.invoke()
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

            val injector: (String) -> Unit = { jsCode ->
                // A03: jsCode is produced exclusively by [loadScene] via Gson
                // serialisation — it never contains untrusted/user data in executable form.
                browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url ?: "", 0)
            }

            val bridge = ExcalidrawJsBridge(
                injector = injector,
                readyHandler = readyHandler,
                jsQueryDispose = { jsQuery.dispose() },
                sceneChangeHandler = sceneChangeHandler,
                // A03: jsQuery.inject(payloadVar) produces the safe native
                // JCEF query call expression — no user data concatenation.
                jsQueryInject = { payloadVar -> jsQuery.inject(payloadVar) }
            )

            // Register the JS→Kotlin handler.  The handler receives the raw string
            // sent from JS via the query function.  A03: we do not execute the
            // received string as code — we only parse it via Gson.
            jsQuery.addHandler { message ->
                val parsed = BridgeMessage.fromJson(message)
                when (parsed) {
                    is BridgeMessage.SceneChange -> {
                        ApplicationManager.getApplication()?.invokeLater {
                            sceneChangeHandler(parsed.payload)
                        }
                    }
                    is BridgeMessage.ExportResult -> {
                        // Dispatch to the one-shot callback slot on the bridge.
                        // The bridge is captured here via the closure — thread-safe
                        // because exportResultCallback is @Volatile.
                        val cb = bridge.exportResultCallback
                        if (cb != null) {
                            bridge.exportResultCallback = null
                            ApplicationManager.getApplication()?.invokeLater {
                                cb(parsed.payload)
                            }
                        }
                    }
                    is BridgeMessage.PngExtracted -> {
                        // Dispatch to the one-shot pngExtractedCallback slot on the bridge.
                        val cb = bridge.pngExtractedCallback
                        if (cb != null) {
                            bridge.pngExtractedCallback = null
                            ApplicationManager.getApplication()?.invokeLater {
                                cb(parsed)
                            }
                        }
                    }
                    is BridgeMessage.PngExported -> {
                        // Dispatch to the one-shot pngExportedCallback slot on the bridge.
                        val cb = bridge.pngExportedCallback
                        if (cb != null) {
                            bridge.pngExportedCallback = null
                            ApplicationManager.getApplication()?.invokeLater {
                                cb(parsed)
                            }
                        }
                    }
                    is BridgeMessage.Ready, null -> {
                        // Treat anything that isn't a typed message as a ready signal
                        // (backwards-compatible with existing single-handler design).
                        readyHandler(message)
                        if (parsed == null) {
                            LOG.warn("ExcalidrawJsBridge: unrecognised JS→Kotlin payload (discarded)")
                        }
                    }
                    else -> readyHandler(message)
                }
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
