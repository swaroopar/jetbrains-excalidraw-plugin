package com.swaroop.excalidraw.plugin.jcef

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JcefShortcutProvider
import com.swaroop.excalidraw.plugin.library.LibraryImport
import com.swaroop.excalidraw.plugin.theme.ThemeMapper
import com.swaroop.excalidraw.plugin.util.runOnEdtOrNow
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.KeyStroke

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
    private val browser: JBCefBrowser?,
    /**
     * Seam for the load-lifecycle operations (navigate, dispose, react to load end/error,
     * schedule the retry reload) this host depends on. [invoke] wires a real
     * [JBCefBrowserHandle]; [createForTest] defaults to a [FakeCefBrowserHandle] so tests
     * can drive [registerLoadLifecycle] without reflection into this class's private state.
     */
    private val browserHandle: CefBrowserHandle
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
         * Builds the URL to navigate to, appending the IDE's current theme as a
         * `?theme=light|dark` query param on [START_URL].
         *
         * AC-E4-01 (no theme flash): index.jsx reads this query param to seed its
         * React.useState initial value, so the very first paint already matches the
         * IDE's theme — instead of always rendering "light" first and only switching
         * to dark after ExcalidrawThemeController.pushCurrentTheme() fires on loadEnd
         * (which is otherwise visible to the user as a light-then-dark flash).
         *
         * [ExcalidrawSchemeHandler.extractPath] strips this query string before
         * resolving the classpath resource, so it has no effect on which file is served.
         */
        internal fun startUrlWithTheme(): String {
            val theme = ThemeMapper.currentExcalidrawTheme()
            return "$START_URL?theme=$theme"
        }

        /**
         * Max number of times to reload [START_URL] when the initial navigation fails
         * because the `excalidraw://` scheme isn't registered yet (startup race).
         */
        private const val MAX_SCHEME_RELOADS: Int = 20

        /** Delay between scheme-not-ready reload attempts (ms). */
        private const val SCHEME_RELOAD_DELAY_MS: Int = 250

        /**
         * Describes one of the six edit shortcuts [JBCefBrowser] auto-binds to native
         * (no-op-on-canvas) `CefFrame` edit commands on macOS: the Swing [KeyStroke] used to
         * register/trigger the forwarding action, plus how to actually replay it against the
         * canvas — see [forwardShortcutToCanvas] for why copy/cut/paste and
         * selectAll/undo/redo need two different replay mechanisms.
         *
         * Exactly one of [nativeCommand] (for copy/cut/paste) or the `js*` fields (for
         * selectAll/undo/redo) is set per instance.
         */
        private class MacEditShortcut(
            val keyStroke: KeyStroke,
            val nativeCommand: ((CefFrame) -> Unit)? = null,
            val jsKey: String? = null,
            val jsCode: String? = null,
            val jsKeyCode: Int = 0,
            val shift: Boolean = false
        )

        /**
         * The six edit shortcuts, expressed directly from [KeyEvent] VK_ constants and
         * [Toolkit.getMenuShortcutKeyMaskEx] (⌘ on macOS) rather than looked up by action ID
         * — see [releaseEditShortcutsToCanvas] for why.
         *
         * Copy/Cut/Paste use [CefFrame]'s native `copy()`/`cut()`/`paste()` commands: bundled
         * Excalidraw's own copy/cut/paste actions are registered with `keyTest: undefined` and
         * are driven *only* by genuine `document` `copy`/`cut`/`paste` `ClipboardEvent`s (see
         * [forwardShortcutToCanvas]), which is exactly what these native commands trigger.
         *
         * Select All/Undo/Redo instead use a synthetic JS `keydown`/`keyup` replay: bundled
         * Excalidraw's corresponding actions *do* declare a `keyTest` and run off its own
         * generic (JS-level, not native-event-level) keyboard shortcut dispatcher, which reads
         * plain [KeyboardEvent] fields and doesn't require a browser-trusted event.
         */
        private val MAC_EDIT_SHORTCUTS: List<MacEditShortcut> by lazy {
            val cmd = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
            listOf(
                MacEditShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_X, cmd), nativeCommand = { it.cut() }),
                MacEditShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, cmd), nativeCommand = { it.copy() }),
                MacEditShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, cmd), nativeCommand = { it.paste() }),
                MacEditShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_A, cmd), jsKey = "a", jsCode = "KeyA", jsKeyCode = KeyEvent.VK_A),
                MacEditShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, cmd), jsKey = "z", jsCode = "KeyZ", jsKeyCode = KeyEvent.VK_Z),
                MacEditShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_Z, cmd or InputEvent.SHIFT_DOWN_MASK),
                    jsKey = "z", jsCode = "KeyZ", jsKeyCode = KeyEvent.VK_Z, shift = true
                )
            )
        }

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
                .setUrl(startUrlWithTheme())
                .build()
            val host = ExcalidrawJcefHost(browser, JBCefBrowserHandle(browser))
            host.registerLoadLifecycle()
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
         *
         * [browserHandle] defaults to a throwaway [FakeCefBrowserHandle]; pass your own
         * instance to drive this host's load lifecycle from a test — e.g.
         * `fakeHandle.simulateLoadEnd()` to fire registered [addLoadEndListener] callbacks,
         * or `fakeHandle.simulateLoadError(...)` to exercise the [shouldRetrySchemeLoad]
         * retry path — instead of reflection into this class's private state.
         *
         * A09: not exposed via production API; does not log internal state.
         */
        fun createForTest(browserHandle: CefBrowserHandle = FakeCefBrowserHandle()): ExcalidrawJcefHost =
            ExcalidrawJcefHost(browser = null, browserHandle = browserHandle).also { it.registerLoadLifecycle() }
    }

    /**
     * Listeners registered via [addLoadEndListener].
     * Access is guarded by [disposed] to satisfy AD-4 and A05.
     */
    private val loadEndListeners: MutableList<() -> Unit> = mutableListOf()

    /**
     * True once [dispose] has been called.
     * After disposal, the load-end firing is a no-op (A05 — stale callbacks suppressed).
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
                    if (targetUrl != null && handler != null && LibraryImport.isLibraryBrowseRequest(targetUrl)) {
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
     * re-binds them so they actually reach Excalidraw's own copy/cut/paste/select-all/undo/redo
     * handling.
     *
     * On macOS, [JBCefBrowser] auto-registers [JcefShortcutProvider] actions on its component
     * ([JBCefBrowser.createComponent] → `if (SystemInfo.isMac)`), binding
     * `$Copy`/`$Paste`/`$Cut`/`$SelectAll`/`$Undo`/`$Redo` to native `CefFrame` edit commands
     * (`CefFrame.copy()`/`.paste()`/etc) — but resolved via
     * [com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT], which never
     * correctly resolves to this browser's own [JBCefBrowser] instance from inside a custom
     * [com.intellij.openapi.fileEditor.FileEditor] (unlike a typical action-system-integrated
     * tool window), so [JcefShortcutProvider]'s own actions silently do nothing here — ⌘C/⌘V/
     * ⌘Z/⌘A/… appear to do nothing.
     *
     * The fix: re-register the *same* shortcuts directly on the browser component, bound to
     * [forwardShortcutToCanvas], which invokes the equivalent behavior against *this* host's own
     * [browser] reference directly — no fragile component/context resolution involved. Inspecting
     * the bundled Excalidraw JS (`excalidraw-bundle/node_modules/@excalidraw/excalidraw/dist/prod/index.js`)
     * shows its `copy`/`paste` actions are registered with `keyTest: undefined` and are *only*
     * driven by genuine `document`-level `copy`/`paste` `ClipboardEvent` listeners (`onCopy`/
     * `pasteFromClipboard`); `cut` has both a `keyTest` and an `onCut` native-event listener.
     * So for Copy/Cut/Paste, [forwardShortcutToCanvas] calls [CefFrame]'s native `copy()`/`cut()`/
     * `paste()` — the same JCEF API [JcefShortcutProvider] itself uses — which triggers a real,
     * trusted `ClipboardEvent` on the page exactly like a native keystroke would.
     *
     * Select All/Undo/Redo, by contrast, *do* declare a `keyTest` and run off Excalidraw's own
     * generic (JS-level) keyboard-shortcut dispatcher, which only inspects plain
     * [KeyboardEvent] fields and doesn't require a browser-trusted event or a DOM selection — so
     * for those three, [forwardShortcutToCanvas] instead dispatches a synthetic
     * `KeyboardEvent('keydown'/'keyup')` via [CefBrowser.executeJavaScript]. (A synthetic
     * `keydown` alone does *not* work for Copy/Cut/Paste: dispatching one from JS was tried
     * first, but Excalidraw's `keyTest: undefined` on those three actions means nothing is
     * listening for it — only a real `ClipboardEvent` triggers their handling.)
     *
     * Only runs on macOS ([SystemInfo.isMac]) — that's the only platform where [JBCefBrowser]
     * auto-binds these shortcuts in the first place (see [JBCefBrowser]'s `createComponent`,
     * which only calls [JcefShortcutProvider.registerShortcuts] when `SystemInfo.isMac`);
     * Windows/Linux never intercept Ctrl+C/V/etc. here; the keystroke already reaches the
     * Chromium renderer natively, so no forwarding is needed there.
     *
     * Deliberately does **not** depend on [JcefShortcutProvider.getActions] to find out *which*
     * shortcuts to bind: that list is empty whenever [JcefShortcutProvider]'s own internal
     * `isSupportedByJCefApi()` check fails for the bundled JCEF version (an internal,
     * version-fragile platform API) — in that case [JcefShortcutProvider] registered nothing in
     * the first place, so unregistering it and looping over the same (now-empty) list here would
     * silently bind nothing too, leaving ⌘C/⌘V exactly as broken as before with no error
     * anywhere. Instead, [MAC_EDIT_SHORTCUTS] below hard-codes the six shortcuts directly from
     * [KeyEvent] VK_ constants plus the platform's own [Toolkit.getMenuShortcutKeyMaskEx] (⌘ on
     * macOS), so this method's own registration never depends on that provider being available
     * or up to date.
     *
     * Still best-effort unregisters [JcefShortcutProvider]'s own bindings (if any) first, so
     * there is never more than one competing registration for the same shortcut on the same
     * component; wrapped in `runCatching` since that class is an internal platform API that
     * could change shape across IDE versions, and its absence must never break editor open.
     */
    private fun releaseEditShortcutsToCanvas() {
        val component = browser?.component ?: return
        if (!SystemInfo.isMac) return
        runCatching {
            JcefShortcutProvider.getActions().forEach { pair -> pair.second.unregisterCustomShortcutSet(component) }
        }.onFailure { LOG.debug("Excalidraw: JcefShortcutProvider unavailable, nothing to unregister", it) }
        MAC_EDIT_SHORTCUTS.forEach { shortcut ->
            val forwardingAction = DumbAwareAction.create { forwardShortcutToCanvas(shortcut) }
            forwardingAction.registerCustomShortcutSet(CustomShortcutSet(shortcut.keyStroke), component)
        }
    }

    /**
     * Replays [shortcut] against the canvas — see [releaseEditShortcutsToCanvas] for the full
     * rationale for the two different replay mechanisms used here:
     *  - Copy/Cut/Paste: invokes [shortcut]'s [MacEditShortcut.nativeCommand] (`CefFrame.copy()`/
     *    `.cut()`/`.paste()`) against the browser's focused frame (falling back to the main frame
     *    if nothing is focused yet), triggering a real, trusted `ClipboardEvent`.
     *  - Select All/Undo/Redo: dispatches a synthetic `keydown`/`keyup` [KeyboardEvent] pair via
     *    [CefBrowser.executeJavaScript].
     *
     * A03: [shortcut]'s fields all come from the fixed, hard-coded [MAC_EDIT_SHORTCUTS] table —
     * never from user/page-controlled input — so any JS built here is a static script with no
     * injectable content, not string-built from untrusted data.
     */
    private fun forwardShortcutToCanvas(shortcut: MacEditShortcut) {
        val cefBrowser = browser?.cefBrowser ?: return
        val nativeCommand = shortcut.nativeCommand
        if (nativeCommand != null) {
            val frame = cefBrowser.focusedFrame ?: cefBrowser.mainFrame ?: return
            nativeCommand(frame)
            return
        }
        val js = """
            (function () {
              var opts = {
                key: '${shortcut.jsKey}', code: '${shortcut.jsCode}',
                keyCode: ${shortcut.jsKeyCode}, which: ${shortcut.jsKeyCode},
                metaKey: true, ctrlKey: false, shiftKey: ${shortcut.shift}, altKey: false,
                bubbles: true, cancelable: true
              };
              var target = document.activeElement || document;
              target.dispatchEvent(new KeyboardEvent('keydown', opts));
              target.dispatchEvent(new KeyboardEvent('keyup', opts));
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
    }

    /**
     * Wires this host's load-lifecycle reactions onto [browserHandle]: [fireLoadEnd] on
     * every main-frame load end, and the scheme-not-ready retry (via
     * [shouldRetrySchemeLoad]/[armForReload]) on a main-frame load error.
     *
     * Called by both [invoke] (production, [browserHandle] is a [JBCefBrowserHandle]) and
     * [createForTest] (test mode, [browserHandle] is a [FakeCefBrowserHandle]) — the same
     * reaction logic runs either way, driven through [CefBrowserHandle] instead of directly
     * touching a [org.cef.handler.CefLoadHandlerAdapter] here.
     */
    private fun registerLoadLifecycle() {
        browserHandle.onLoadEnd { fireLoadEnd() }
        browserHandle.onLoadError { isMainFrame, failedUrl ->
            if (shouldRetrySchemeLoad(isMainFrame, failedUrl)) {
                // Retry on the EDT after a short delay; the `disposed` guard makes a
                // late-firing retry a no-op if the editor is closed meanwhile.
                browserHandle.scheduleReload(SCHEME_RELOAD_DELAY_MS.toLong()) {
                    if (!disposed) {
                        // The failed (ERR_UNKNOWN_URL_SCHEME) load already fired onLoadEnd
                        // for its error page, tripping the once-only [fired] guard. Reset
                        // it so this reload's onLoadEnd re-fires the scene-push —
                        // otherwise the first restored editor on IDE restart renders empty.
                        armForReload()
                        browserHandle.loadUrl(startUrlWithTheme())
                    }
                }
            }
        }
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
     * Re-arms the once-only [fireLoadEnd] so the next main-frame onLoadEnd fires the
     * registered listeners again. Called from [registerLoadLifecycle]'s scheme-not-ready
     * retry reload (the failed load's error page already tripped [fired]); the registered
     * listeners are idempotent (re-install the return channel, re-request the scene), so
     * re-running them on the successful reload is what restores the scene.
     */
    private fun armForReload() {
        if (disposed) return
        fired = false
    }

    /**
     * Fires all registered [loadEndListeners] exactly once, on the EDT.
     * Subsequent calls are no-ops (fired-once invariant).
     * Wired to [browserHandle]'s main-frame onLoadEnd event by [registerLoadLifecycle]
     * (production and test mode alike — see [CefBrowserHandle]).
     *
     * A05: guarded by [disposed] — no callbacks after disposal.
     */
    private fun fireLoadEnd() {
        if (disposed || fired) return
        fired = true

        val snapshot = loadEndListeners.toList()
        if (snapshot.isEmpty()) return

        runOnEdtOrNow {
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
     * Disposes the underlying browser (via [browserHandle]), releasing all JCEF resources.
     * Called automatically by the IDE when the parent [ExcalidrawFileEditor] is disposed —
     * and directly by unit tests, since [browserHandle] is a no-op [FakeCefBrowserHandle]
     * in test mode (no separate test-only dispose method needed).
     * AD-3: clean disposal chain — no leaks.
     * A05: sets [disposed] flag so that subsequent [fireLoadEnd] calls are no-ops.
     */
    override fun dispose() {
        disposed = true
        loadEndListeners.clear()
        browserHandle.dispose()
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
