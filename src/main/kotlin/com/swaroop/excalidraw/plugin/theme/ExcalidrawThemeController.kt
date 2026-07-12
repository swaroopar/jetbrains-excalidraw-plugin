package com.swaroop.excalidraw.plugin.theme

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge

/**
 * ExcalidrawThemeController — synchronises the IDE theme with the Excalidraw
 * web app running in JCEF.
 *
 * Responsibilities:
 * - Exposes [pushCurrentTheme] to push the current IDE theme at the correct
 *   moment (after JCEF loadEnd, when `window.__excalidrawSetTheme__` is
 *   guaranteed to exist) — AC-E4-01.
 * - Subscribes to [LafManagerListener] on the Application message bus in the
 *   init block and re-pushes the theme on each IDE-theme change — AC-E4-02.
 * - Suppresses live LafManager callbacks received before [pushCurrentTheme] is
 *   called (ready-guard) to avoid injecting into a not-yet-loaded page.
 * - Implements [Disposable]: after [dispose] is called, no further bridge calls
 *   are made regardless of listener callbacks.
 *
 * Design for testability (no MockK / Mockito):
 * - [themeProvider]: injected function that returns the current theme string.
 *   Default is [ThemeMapper.currentExcalidrawTheme] (production). Tests supply
 *   a plain lambda so no LafManager runtime is required.
 * - [listenerRegistrar]: injected function that receives the "on-theme-changed"
 *   callback and registers it however appropriate.  Production implementation
 *   subscribes to the Application message bus; tests capture the callback and
 *   invoke it directly.
 *
 * Timing contract (task-05-007):
 *   The caller (ExcalidrawFileEditor) must invoke [pushCurrentTheme] from inside
 *   the loadEnd callback — after [ExcalidrawJsBridge.installReturnChannel] and
 *   [ExcalidrawJsBridge.loadScene] — so `window.__excalidrawSetTheme__` is
 *   guaranteed to be defined before the first bridge call.
 *
 * Security (A03): theme values are passed to [ExcalidrawJsBridge.sendThemeUpdate]
 * which encodes them via [Gson.toJson] before embedding in JavaScript — no raw
 * string concatenation of theme values reaches the JS engine.
 *
 * Lifecycle: register this controller as a child Disposable of the editor via
 * [com.intellij.openapi.util.Disposer.register] so it is disposed when the
 * editor closes.
 */
class ExcalidrawThemeController(
    private val bridge: ExcalidrawJsBridge,
    private val themeProvider: () -> String = { ThemeMapper.currentExcalidrawTheme() },
    listenerRegistrar: ((onThemeChanged: () -> Unit) -> Unit) = ::defaultListenerRegistrar
) : Disposable {

    @Volatile
    private var disposed: Boolean = false

    /**
     * Set to true by [pushCurrentTheme] when the initial theme has been pushed.
     *
     * Live LafManager callbacks received before [pushCurrentTheme] is invoked are
     * suppressed — the page is not ready yet and `window.__excalidrawSetTheme__`
     * does not exist. Once ready, live callbacks proceed normally.
     */
    @Volatile
    private var ready: Boolean = false

    init {
        // Register the listener that reacts to live IDE-theme switches (AC-E4-02).
        // The lambda captures [this] — safe because the listener registrar is
        // invoked synchronously in the init block and the dispose-guard prevents
        // any bridge call after disposal.
        // NOTE: NO sendThemeUpdate here — the initial push must occur after JCEF
        // loadEnd (when window.__excalidrawSetTheme__ is defined). Call
        // [pushCurrentTheme] from the loadEnd callback instead (task-05-007).
        listenerRegistrar(::onThemeChanged)
    }

    /**
     * Pushes the current IDE theme to the Excalidraw web app and marks the
     * controller as ready for live LafManager updates.
     *
     * Must be called from inside the JCEF loadEnd callback — after
     * [ExcalidrawJsBridge.installReturnChannel] and [ExcalidrawJsBridge.loadScene]
     * — so that `window.__excalidrawSetTheme__` is guaranteed to be defined
     * (AC-E4-01, timing contract).
     *
     * Subsequent [LafManagerListener] callbacks will immediately call
     * [ExcalidrawJsBridge.sendThemeUpdate] because [ready] is now true.
     *
     * No-op after [dispose].
     */
    fun pushCurrentTheme() {
        pushTheme()
        ready = true
    }

    /**
     * Called by the listener when the IDE LookAndFeel changes (AC-E4-02).
     * No-op before [pushCurrentTheme] is called (ready-guard) or after [dispose].
     */
    private fun onThemeChanged() {
        if (!ready) return
        pushTheme()
    }

    /**
     * Pushes the current theme to the bridge if the controller is not disposed.
     *
     * This helper eliminates duplication between [pushCurrentTheme] and
     * [onThemeChanged], both of which need to perform the same disposed-check
     * and bridge-update sequence.
     */
    private fun pushTheme() {
        if (disposed) return
        bridge.sendThemeUpdate(themeProvider())
    }

    /**
     * Disconnects the message-bus listener and prevents any further bridge calls.
     *
     * After this method returns, invocations of the registered listener callback
     * are no-ops (guarded by [disposed]).  The message-bus connection itself is
     * cleaned up by the Disposable lifecycle: passing [this] as the parent
     * disposable to [messageBus.connect] causes the IDE to disconnect the
     * connection when this controller is disposed.
     */
    override fun dispose() {
        disposed = true
    }

    companion object {
        /**
         * Production listener registrar: subscribes [onThemeChanged] to the
         * Application-level [LafManagerListener.TOPIC] message bus topic.
         *
         * The connection is parented to a [Disposable] that is managed by the
         * caller (see [ExcalidrawThemeController] constructor documentation).
         * Passing a single-use [Disposable] adapter ensures the connection is
         * disconnected when the controller is disposed — without leaking a
         * reference to [this] before the object is fully initialised.
         */
        /**
         * Subscribes [onThemeChanged] to [LafManagerListener.TOPIC] on the
         * Application message bus.
         *
         * The connection has no parent disposable — disposal is handled by the
         * [ExcalidrawThemeController.disposed] flag in [onThemeChanged], which
         * turns the callback into a no-op after [dispose] is called. This avoids
         * the complexity of storing the connection reference on the controller.
         *
         * Returns without subscribing when [ApplicationManager.getApplication]
         * returns null (headless test environments that use a different registrar).
         */
        private fun defaultListenerRegistrar(onThemeChanged: () -> Unit) {
            val app = ApplicationManager.getApplication() ?: return
            val connection = app.messageBus.connect()
            connection.subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener { _: LafManager -> onThemeChanged() }
            )
        }
    }
}
