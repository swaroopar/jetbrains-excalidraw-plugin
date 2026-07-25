package com.swaroop.excalidraw.plugin.jcef

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.concurrency.EdtScheduledExecutorService
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JcefShortcutProvider
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * ExcalidrawJcefHost: JBCefBrowser wrapper that manages the browser lifecycle.
 *
 * Architecture Decisions enforced here:
 *   AD-2: Excalidraw web app is loaded via the internal `excalidraw://` custom scheme,
 *         never via `http://`, `https://`, or `file://` URLs (NFR1).
 *   AD-3: This class implements [Disposable] and is held as a child-Disposable of
 *         [ExcalidrawFileEditor]. When the editor is closed, the IDE disposal chain
 *         calls [dispose] automatically, which delegates to [JBCefBrowser.dispose]
 *         to release JCEF resources and prevent memory leaks.
 *   AD-4: Scene-push is delayed until [addLoadEndListener] fires. The listener fires
 *         exactly once on the EDT, after which no further callbacks can occur.
 *
 * NFR1 compliance:
 *   - The browser is directed only to [START_URL] (`excalidraw://app/index.html`).
 *   - The custom scheme handler ([ExcalidrawSchemeHandler]) resolves all requests to
 *     classpath resources bundled with the plugin — no remote URLs, no network egress.
 *   - The CSP header set by [ExcalidrawSchemeHandler] further prevents the browser
 *     from fetching external resources.
 *
 * TODO (Phase 02 integration test): Add HeavyPlatformTestCase / UI test that
 *   instantiates ExcalidrawJcefHost with a real IDE environment, verifies that the
 *   JBCefBrowser component is non-null, that loadURL was called with START_URL, and
 *   that dispose() shuts down the browser without errors. This requires a running IDE
 *   instance and cannot run as a plain JUnit 5 unit test.
 */
class ExcalidrawJcefHost private constructor(
    private val browser: JBCefBrowser?
) : Disposable {

    companion object {
        /**
         * URI scheme name for the bundled Excalidraw web app.
         * Used when registering the [JBCefApp.JBCefCustomSchemeHandlerFactory].
         */
        const val SCHEME: String = "excalidraw"

        /**
         * Entry-point URL for the bundled Excalidraw web app.
         * No remote URL — local classpath resource served by [ExcalidrawSchemeHandler].
         * NFR1: no `http://` or `https://` in this constant or anywhere in this class.
         */
        const val START_URL: String = "excalidraw://app/index.html"

        private val LOG = logger<ExcalidrawJcefHost>()

        /**
         * Max number of times to reload [START_URL] when the initial navigation fails
         * because the `excalidraw://` scheme isn't registered yet (startup race).
         */
        private const val MAX_SCHEME_RELOADS: Int = 20

        /** Delay between scheme-not-ready reload attempts (ms). */
        private const val SCHEME_RELOAD_DELAY_MS: Int = 250

        /**
         * Production constructor — creates a real [JBCefBrowser].
         *
         * The `excalidraw://` custom scheme handler is NOT registered here. It must be
         * registered exactly once, before JBCefApp is initialized — otherwise
         * [JBCefApp.addCefCustomSchemeHandlerFactory] throws "JBCefApp has already been
         * initialized!". That one-time registration is done by
         * [ExcalidrawSchemeHandlerRegistrar] (an ApplicationInitializedListener), which
         * runs at IDE startup before any browser is created.
         */
        operator fun invoke(): ExcalidrawJcefHost {
            val browser = JBCefBrowser.createBuilder()
                .setUrl(START_URL)
                .build()
            val host = ExcalidrawJcefHost(browser)
            host.registerLoadHandler()
            host.registerLifeSpanHandler()
            host.releaseEditShortcutsToCanvas()
            // Diagnostics: launch with -Dexcalidraw.devtools=true (Help > Edit Custom VM
            // Options) to open the JCEF DevTools window and inspect the browser console /
            // network for a blank-canvas issue. Off by default.
            if (System.getProperty("excalidraw.devtools") == "true") {
                browser.openDevtools()
            }
            return host
        }

        /**
         * Test factory — creates a host without a real [JBCefBrowser].
         * Used only by unit tests that cannot access the JCEF runtime.
         * A09: not exposed via production API; does not log internal state.
         */
        fun createForTest(): ExcalidrawJcefHost =
            ExcalidrawJcefHost(browser = null)
    }

    /**
     * Listeners registered via [addLoadEndListener].
     * Access is guarded by [disposed] to satisfy AD-4 and A05.
     */
    private val loadEndListeners: MutableList<() -> Unit> = mutableListOf()

    /**
     * True once [dispose] or [disposeForTest] has been called.
     * After disposal, [fireLoadEnd] is a no-op (A05 — stale callbacks suppressed).
     */
    @Volatile
    private var disposed: Boolean = false

    /**
     * Tracks whether the listeners have already fired.
     * Guarantees the "exactly once" invariant (AD-4).
     */
    @Volatile
    private var fired: Boolean = false

    /**
     * Counts reload attempts triggered by [START_URL] failing to load because the
     * `excalidraw://` scheme handler is not registered yet (startup race — see
     * [registerLoadHandler]). Bounded by [MAX_SCHEME_RELOADS].
     */
    private val schemeReloadAttempts = AtomicInteger(0)

    /**
     * Optional hook invoked (on the EDT) when the user opens Excalidraw's
     * "Browse libraries" link, with the full libraries.excalidraw.com URL.
     * [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor] sets this to open the
     * in-IDE library browser so the chosen library round-trips back into the editor.
     * When null, the URL falls back to the external system browser.
     */
    var onBrowseLibraries: ((String) -> Unit)? = null

    /**
     * Routes pop-up windows (and other new-window navigations) to the user's default
     * external browser instead of silently dropping them.
     *
     * Excalidraw's "Browse libraries" button — and element hyperlinks — are anchors with
     * a `target` (e.g. `_excalidraw_libraries`), which JCEF treats as pop-up requests. With
     * no [org.cef.handler.CefLifeSpanHandler] the pop-up is never created, so clicking does
     * nothing. We cancel the embedded pop-up and open the http(s) URL via [BrowserUtil] so
     * libraries.excalidraw.com (and any external link) opens in the system browser.
     *
     * Non-http(s) targets are ignored (NFR1: the excalidraw:// app itself never navigates
     * out, and we must not hand arbitrary schemes to the OS).
     */
    private fun registerLifeSpanHandler() {
        browser?.jbCefClient?.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?
                ): Boolean {
                    val handler = onBrowseLibraries
                    if (targetUrl != null && handler != null && targetUrl.contains("libraries.excalidraw.com")) {
                        // "Browse libraries" → in-IDE library browser (round-trips the
                        // chosen library back into the editor). onBeforePopup runs on a CEF
                        // thread, not the EDT — opening a dialog / creating a JBCefBrowser
                        // off the EDT silently fails AND corrupts shared CEF state (all later
                        // browsers go blank), so hop to the EDT first.
                        LOG.info("Excalidraw: opening library browser for: $targetUrl")
                        ApplicationManager.getApplication().invokeLater { handler(targetUrl) }
                    } else if (targetUrl != null &&
                        (targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))
                    ) {
                        LOG.info("Excalidraw: opening external link in system browser: $targetUrl")
                        BrowserUtil.browse(targetUrl)
                    }
                    // Always cancel the embedded pop-up — we either handled it (library
                    // browser / external browser) or it targets a scheme we don't allow.
                    return true
                }
            },
            browser.cefBrowser
        )
    }

    /**
     * Frees the platform edit shortcuts (Ctrl/⌘ + C/V/X/A/Z/Y) for the Excalidraw canvas and
     * re-binds them to forward the *real* key event straight into the Chromium renderer.
     *
     * On macOS, [JBCefBrowser] auto-registers [JcefShortcutProvider] actions on its
     * component ([JBCefBrowser.createComponent] → `if (SystemInfo.isMac)`), binding
     * `$Copy`/`$Paste`/`$Cut`/`$SelectAll`/`$Undo`/`$Redo` to native `CefFrame` edit
     * commands (`CefFrame.copy()`/`.paste()`/etc). Those native commands only affect a
     * focused *editable DOM element* (e.g. `<input>`/`contenteditable`) — but Excalidraw is
     * a `<canvas>` app that implements copy/paste/cut/select-all/undo/redo entirely in its
     * own JS `keydown` handlers. So on macOS the keystroke is grabbed by
     * [JcefShortcutProvider], routed to a native command that no-ops on the canvas, and
     * Excalidraw's JS handler never even sees a `keydown` event — ⌘C/⌘V/⌘Z/⌘A/… appear to
     * do nothing.
     *
     * Merely unregistering [JcefShortcutProvider]'s bindings is not sufficient: on macOS,
     * ⌘-based accelerators are otherwise intercepted by the shared screen menu bar before
     * they ever reach the embedded browser's native view, so simply freeing the shortcut
     * does not make the keystroke "fall through" — it just becomes an unhandled, silently
     * swallowed accelerator (matching the symptom of ⌘C/⌘V doing nothing at all).
     *
     * The fix: re-register the *same* shortcuts on the browser component, but bind them to
     * [forwardKeyEventToCanvas] instead, which uses [CefBrowser.sendKeyEvent] — the JCEF API
     * built for exactly this purpose — to inject a synthetic native key event directly into
     * the Chromium renderer. That is indistinguishable, from Excalidraw's perspective, from
     * the browser having received the keystroke natively (as already happens on
     * Windows/Linux, where the platform never intercepts these shortcuts in the first
     * place), so its own `keydown`-driven copy/paste/undo/redo logic runs normally — using
     * the fast, synchronous native clipboard path instead of the slower async
     * `navigator.clipboard` bridge (see [com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge.installClipboardBridge]).
     *
     * No-op off macOS (nothing was registered by [JcefShortcutProvider] there) and on JCEF
     * API versions too old to expose the actions ([JcefShortcutProvider.getActions] returns
     * an empty list). Guarded by `runCatching` so a platform API change can never break
     * editor open.
     */
    private fun releaseEditShortcutsToCanvas() {
        val component = browser?.component ?: return
        runCatching {
            val actionManager = ActionManager.getInstance()
            JcefShortcutProvider.getActions().forEach { pair ->
                val actionId = pair.first
                val noOpAction = pair.second
                noOpAction.unregisterCustomShortcutSet(component)
                val shortcutSet = actionManager.getAction(actionId)?.shortcutSet ?: return@forEach
                val forwardingAction = DumbAwareAction.create { event ->
                    (event.inputEvent as? KeyEvent)?.let { forwardKeyEventToCanvas(it) }
                }
                forwardingAction.registerCustomShortcutSet(shortcutSet, component)
            }
        }.onFailure { LOG.warn("Excalidraw: could not release JCEF edit shortcuts to the canvas", it) }
    }

    /**
     * Injects [source] into the Chromium renderer via [CefBrowser.sendKeyEvent], the JCEF API
     * for delivering a synthetic native key event as if the OS had dispatched it directly to
     * the browser. Mirrors the PRESSED → TYPED → RELEASED sequence JCEF itself sends for a
     * real keystroke (see `CefBrowserOsr`'s key listener), so Excalidraw's `keydown`/`keyup`
     * handlers observe the same event sequence they would from native input.
     *
     * [source] is the [KeyEvent] captured from the triggering [AnActionEvent.getInputEvent]
     * (see [releaseEditShortcutsToCanvas]) — its key code, key char, and modifiers are reused
     * verbatim so the forwarded event matches what the user actually pressed.
     */
    private fun forwardKeyEventToCanvas(source: KeyEvent) {
        val cefBrowser = browser?.cefBrowser ?: return
        val uiComponent = cefBrowser.getUIComponent() ?: return
        val now = System.currentTimeMillis()
        cefBrowser.sendKeyEvent(
            KeyEvent(uiComponent, KeyEvent.KEY_PRESSED, now, source.modifiersEx, source.keyCode, source.keyChar)
        )
        if (source.keyChar != KeyEvent.CHAR_UNDEFINED) {
            cefBrowser.sendKeyEvent(
                KeyEvent(uiComponent, KeyEvent.KEY_TYPED, now, source.modifiersEx, KeyEvent.VK_UNDEFINED, source.keyChar)
            )
        }
        cefBrowser.sendKeyEvent(
            KeyEvent(uiComponent, KeyEvent.KEY_RELEASED, now, source.modifiersEx, source.keyCode, source.keyChar)
        )
    }

    /**
     * Registers a [CefLoadHandlerAdapter] on the underlying browser that will call
     * [fireLoadEnd] when JCEF emits its page-load-complete event.
     * Only called in production mode — test mode uses direct [fireLoadEnd] invocation.
     */
    private fun registerLoadHandler() {
        browser?.jbCefClient?.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int
                ) {
                    // Only fire for the main frame (frame.isMain guarantees we don't
                    // react to sub-frame loads such as iframes — A03: no injection via sub-frames).
                    if (frame?.isMain == true) {
                        fireLoadEnd()
                    }
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    if (errorCode != null) {
                        LOG.debug("Excalidraw: onLoadError errorCode=$errorCode url=$failedUrl")
                    }
                    if (shouldRetrySchemeLoad(frame?.isMain == true, failedUrl)) {
                        // Retry on the EDT after a short delay; the `disposed` guard makes a
                        // late-firing retry a no-op if the editor is closed meanwhile.
                        EdtScheduledExecutorService.getInstance().schedule(
                            {
                                if (!disposed) {
                                    // The failed (ERR_UNKNOWN_URL_SCHEME) load already fired
                                    // onLoadEnd for its error page, tripping the once-only
                                    // [fired] guard. Reset it so this reload's onLoadEnd
                                    // re-fires the scene-push — otherwise the first restored
                                    // editor on IDE restart renders empty.
                                    armForReload()
                                    browser.cefBrowser.loadURL(START_URL)
                                }
                            },
                            SCHEME_RELOAD_DELAY_MS.toLong(),
                            TimeUnit.MILLISECONDS
                        )
                    }
                }
            },
            browser.cefBrowser
        )
    }

    /**
     * Decides whether a failed page load should trigger a reload of [START_URL].
     *
     * Returns true (and consumes one of the [MAX_SCHEME_RELOADS] attempts) only for a
     * main-frame failure on the `excalidraw://` scheme while the host is alive. This is
     * the startup-race recovery: a restored editor can navigate before
     * [ExcalidrawSchemeHandlerRegistrar] registers the scheme, yielding
     * `ERR_UNKNOWN_URL_SCHEME`; registration completes a moment later, so a bounded
     * series of retries lets the page load without the user reopening the file.
     *
     * Extracted from the load handler so the decision (and its retry cap) is unit-testable
     * without a live JCEF browser.
     */
    internal fun shouldRetrySchemeLoad(isMainFrame: Boolean, failedUrl: String?): Boolean {
        if (!isMainFrame || disposed) return false
        val isSchemeUrl = failedUrl == START_URL || failedUrl?.startsWith("$SCHEME://") == true
        if (!isSchemeUrl) return false
        val attempt = schemeReloadAttempts.incrementAndGet()
        if (attempt > MAX_SCHEME_RELOADS) {
            LOG.warn("Excalidraw: giving up loading $START_URL after $attempt attempts")
            return false
        }
        LOG.info(
            "Excalidraw: $START_URL failed to load (scheme likely not registered yet) — " +
                "retry $attempt/$MAX_SCHEME_RELOADS"
        )
        return true
    }

    /**
     * Registers [listener] to be invoked once, on the EDT, when the JCEF page has
     * finished loading (AD-4). The listener is guaranteed not to fire after [dispose].
     *
     * Multiple listeners may be registered; each fires exactly once.
     *
     * @param listener A zero-argument lambda. Must not throw unchecked exceptions; any
     *   exception raised inside will propagate to the EDT exception handler (A09).
     */
    fun addLoadEndListener(listener: () -> Unit) {
        loadEndListeners.add(listener)
    }

    /**
     * Fires all registered [loadEndListeners] exactly once, on the EDT.
     * Subsequent calls are no-ops (fired-once invariant).
     * Called from the JCEF [CefLoadHandlerAdapter.onLoadEnd] callback (production) or
     * directly via reflection in unit tests.
     *
     * A05: guarded by [disposed] — no callbacks after disposal.
     */
    /**
     * Re-arms the once-only [fireLoadEnd] so the next main-frame onLoadEnd fires the
     * registered listeners again. Used before a scheme-not-ready retry reload (the failed
     * load's error page already tripped [fired]); the registered listeners are idempotent
     * (re-install the return channel, re-request the scene), so re-running them on the
     * successful reload is what restores the scene.
     */
    internal fun armForReload() {
        if (disposed) return
        fired = false
    }

    internal fun fireLoadEnd() {
        if (disposed || fired) return
        fired = true

        val snapshot = loadEndListeners.toList()
        if (snapshot.isEmpty()) return

        val application = try {
            ApplicationManager.getApplication()
        } catch (_: Exception) {
            // In unit-test environments the application may not be available.
            // Fall back to direct invocation on the calling thread.
            null
        }

        if (application != null) {
            application.invokeLater {
                if (!disposed) {
                    snapshot.forEach { it() }
                }
            }
        } else {
            // Test-mode fallback: invoke synchronously so tests can assert call counts.
            if (!disposed) {
                snapshot.forEach { it() }
            }
        }
    }

    /**
     * Returns the Swing component that embeds the JCEF browser.
     * Pass this to [ExcalidrawFileEditor.getComponent].
     * Not callable in test mode (browser is null).
     */
    val component: JComponent
        get() = browser?.component
            ?: error("ExcalidrawJcefHost.component is not available in test mode")

    /**
     * Disposes the underlying [JBCefBrowser], releasing all JCEF resources.
     * Called automatically by the IDE when the parent [ExcalidrawFileEditor] is disposed.
     * AD-3: clean disposal chain — no leaks.
     * A05: sets [disposed] flag so that subsequent [fireLoadEnd] calls are no-ops.
     */
    override fun dispose() {
        disposed = true
        loadEndListeners.clear()
        browser?.dispose()
    }

    /**
     * Lightweight dispose used by unit tests that do not hold a real [JBCefBrowser].
     * Sets [disposed] = true so that [fireLoadEnd] becomes a no-op (same invariant
     * as the production [dispose], but without touching a null browser reference).
     */
    fun disposeForTest() {
        disposed = true
        loadEndListeners.clear()
    }

    /**
     * Returns the underlying [JBCefBrowser] for bridge wiring at construction time.
     *
     * Exposed as a package-internal API so that [ExcalidrawFileEditor] can pass the
     * browser to [com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge.create]
     * without resorting to reflection. The host remains the sole owner of the browser's
     * lifecycle — the bridge only holds a reference for JS injection and does not call
     * [JBCefBrowser.dispose] directly.
     *
     * Returns null in test mode (created via [createForTest]) where no real browser exists.
     *
     * Scope-extension note (task-02-007 review): this accessor was added to eliminate
     * the reflection-based workaround in [ExcalidrawFileEditor.invoke]. It is the only
     * change to this file in task-02-007.
     */
    internal fun browserForBridge(): JBCefBrowser? = browser

    /**
     * [JBCefApp.JBCefCustomSchemeHandlerFactory] that delegates to
     * [ExcalidrawSchemeHandler.Factory] for resource resolution and registers the
     * `excalidraw` scheme with JCEF so the browser routes all `excalidraw://` requests
     * through our handler.
     *
     * Domain name is left empty so the factory handles all hosts under the scheme
     * (e.g. `excalidraw://app/index.html`, `excalidraw://assets/bundle.js`).
     */
    internal class SchemeHandlerFactory : JBCefApp.JBCefCustomSchemeHandlerFactory {

        private val delegate = ExcalidrawSchemeHandler.Factory()

        override fun getSchemeName(): String = SCHEME

        override fun getDomainName(): String = ""

        override fun registerCustomScheme(registrar: CefSchemeRegistrar) {
            // Standard + secure + NON-local scheme so the page gets a normal web origin
            // (excalidraw://app). isLocal=true applies file://-style rules — an OPAQUE
            // origin — under which CSP 'self' matches nothing and same-origin subresources
            // (bundle.js / bundle.css) are refused, leaving the canvas blank. Privacy is
            // still enforced by connect-src 'none' and the bundled-only scheme handler.
            registrar.addCustomScheme(
                SCHEME,
                /* isStandard    = */ true,
                /* isLocal       = */ false,
                /* isDisplayIsolated = */ false,
                /* isSecure      = */ true,
                /* isCorsEnabled = */ true,
                /* isCspBypassing = */ false,
                /* fetchEnabled  = */ true
            )
        }

        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: org.cef.network.CefRequest?
        ): org.cef.handler.CefResourceHandler = delegate.create(browser, frame, schemeName, request)
    }
}
